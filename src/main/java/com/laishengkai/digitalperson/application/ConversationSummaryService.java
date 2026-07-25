package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationEpisodeDraft;
import com.laishengkai.digitalperson.conversation.ConversationEpisodeStore;
import com.laishengkai.digitalperson.conversation.ConversationSummarySnapshot;
import com.laishengkai.digitalperson.conversation.ConversationSummaryStore;
import com.laishengkai.digitalperson.conversation.ConversationSummaryWorkItem;
import com.laishengkai.digitalperson.dialogue.ConversationEpisodeModel;
import com.laishengkai.digitalperson.dialogue.ConversationSummaryModel;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/**
 * Summarizes older raw turns and optionally extracts complete event memories from the same
 * stable batch without blocking normal dialogue on auxiliary failures.
 */
public final class ConversationSummaryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            ConversationSummaryService.class
    );

    private final ConversationSummaryStore summaryStore;
    private final ConversationSummaryModel summaryModel;
    private final ConversationEpisodeStore episodeStore;
    private final ConversationEpisodeModel episodeModel;
    private final int recentTurnsToKeep;
    private final int batchTurns;

    public ConversationSummaryService(
            ConversationSummaryStore summaryStore,
            ConversationSummaryModel summaryModel,
            int recentTurnsToKeep,
            int batchTurns
    ) {
        this(
                summaryStore,
                summaryModel,
                null,
                null,
                recentTurnsToKeep,
                batchTurns
        );
    }

    public ConversationSummaryService(
            ConversationSummaryStore summaryStore,
            ConversationSummaryModel summaryModel,
            ConversationEpisodeStore episodeStore,
            ConversationEpisodeModel episodeModel,
            int recentTurnsToKeep,
            int batchTurns
    ) {
        this.summaryStore = Objects.requireNonNull(
                summaryStore,
                "summaryStore cannot be null"
        );
        this.summaryModel = Objects.requireNonNull(
                summaryModel,
                "summaryModel cannot be null"
        );
        if ((episodeStore == null) != (episodeModel == null)) {
            throw new IllegalArgumentException(
                    "episodeStore and episodeModel must both be configured or both be absent"
            );
        }
        this.episodeStore = episodeStore;
        this.episodeModel = episodeModel;
        this.recentTurnsToKeep = positive(recentTurnsToKeep, "recentTurnsToKeep");
        this.batchTurns = positive(batchTurns, "batchTurns");
    }

    public CompletionStage<Void> summarizeIfNeeded(
            PersonId personId,
            ZoneId localTimeZone,
            Instant summarizedAt
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        ZoneId zone = Objects.requireNonNull(
                localTimeZone,
                "localTimeZone cannot be null"
        );
        Instant now = Objects.requireNonNull(summarizedAt, "summarizedAt cannot be null");

        final CompletionStage<Optional<ConversationSummaryWorkItem>> workStage;
        try {
            workStage = Objects.requireNonNull(
                    summaryStore.findWork(
                            requestedPersonId,
                            recentTurnsToKeep,
                            batchTurns
                    ),
                    "summaryStore work stage cannot be null"
            );
        } catch (RuntimeException error) {
            logSummaryFailure(requestedPersonId, error);
            return CompletableFuture.completedFuture(null);
        }

        return workStage.thenCompose(work -> {
            Optional<ConversationSummaryWorkItem> candidate = Objects.requireNonNull(
                    work,
                    "summary work result cannot be null"
            );
            if (candidate.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            ConversationSummaryWorkItem item = candidate.orElseThrow();
            Optional<String> existingSummary = item.existingSummary()
                    .map(ConversationSummarySnapshot::content);
            CompletionStage<List<ConversationEpisodeDraft>> episodeStage =
                    extractEpisodes(requestedPersonId, item, zone);

            final CompletionStage<String> modelStage;
            try {
                modelStage = Objects.requireNonNull(
                        summaryModel.summarize(existingSummary, item.turns(), zone),
                        "summaryModel stage cannot be null"
                );
            } catch (RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }

            return modelStage.thenCompose(summary -> Objects.requireNonNull(
                    summaryStore.save(
                            requestedPersonId,
                            item,
                            requireSummary(summary),
                            now
                    ),
                    "summaryStore save stage cannot be null"
            )).thenCompose(saved -> {
                if (!Boolean.TRUE.equals(saved)) {
                    LOGGER.info(
                            "Rolling conversation summary update lost optimistic race: personId={}, expectedVersion={}",
                            requestedPersonId,
                            item.expectedVersion()
                    );
                    return CompletableFuture.completedFuture(null);
                }

                LOGGER.info(
                        "Rolling conversation summary updated: personId={}, summarizedTurnCount={}, coveredThroughTurnId={}",
                        requestedPersonId,
                        item.turns().size(),
                        item.coveredThroughTurnId()
                );
                return persistEpisodes(requestedPersonId, item, episodeStage, now);
            });
        }).handle((ignored, failure) -> {
            if (failure != null) {
                logSummaryFailure(requestedPersonId, unwrap(failure));
            }
            return null;
        });
    }

    private CompletionStage<List<ConversationEpisodeDraft>> extractEpisodes(
            PersonId personId,
            ConversationSummaryWorkItem item,
            ZoneId zone
    ) {
        if (episodeModel == null) {
            return CompletableFuture.completedFuture(List.of());
        }
        final CompletionStage<List<ConversationEpisodeDraft>> stage;
        try {
            stage = Objects.requireNonNull(
                    episodeModel.extract(item.turns(), zone),
                    "episodeModel stage cannot be null"
            );
        } catch (RuntimeException error) {
            logEpisodeFailure(personId, "extraction", error);
            return CompletableFuture.completedFuture(List.of());
        }
        return stage.handle((episodes, failure) -> {
            if (failure != null) {
                logEpisodeFailure(personId, "extraction", unwrap(failure));
                return List.of();
            }
            List<ConversationEpisodeDraft> safe = List.copyOf(Objects.requireNonNullElse(
                    episodes,
                    List.of()
            ));
            if (safe.stream().anyMatch(Objects::isNull)) {
                logEpisodeFailure(
                        personId,
                        "extraction",
                        new NullPointerException("episodes cannot contain null")
                );
                return List.of();
            }
            return safe;
        });
    }

    private CompletionStage<Void> persistEpisodes(
            PersonId personId,
            ConversationSummaryWorkItem item,
            CompletionStage<List<ConversationEpisodeDraft>> episodeStage,
            Instant extractedAt
    ) {
        if (episodeStore == null) {
            return CompletableFuture.completedFuture(null);
        }
        return episodeStage.thenCompose(episodes -> {
            if (episodes.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            final CompletionStage<Integer> saveStage;
            try {
                saveStage = Objects.requireNonNull(
                        episodeStore.saveAll(personId, item, episodes, extractedAt),
                        "episodeStore stage cannot be null"
                );
            } catch (RuntimeException error) {
                logEpisodeFailure(personId, "persistence", error);
                return CompletableFuture.completedFuture(null);
            }
            return saveStage.handle((stored, failure) -> {
                if (failure != null) {
                    logEpisodeFailure(personId, "persistence", unwrap(failure));
                    return null;
                }
                LOGGER.info(
                        "Conversation episodes persisted: personId={}, extractedCount={}, insertedCount={}, sourceStartTurnId={}, sourceEndTurnId={}",
                        personId,
                        episodes.size(),
                        Objects.requireNonNullElse(stored, 0),
                        item.sourceStartTurnId(),
                        item.coveredThroughTurnId()
                );
                return null;
            });
        });
    }

    private static String requireSummary(String value) {
        String normalized = Objects.requireNonNull(
                value,
                "summary cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("summary cannot be blank");
        }
        return normalized;
    }

    private static int positive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void logSummaryFailure(PersonId personId, Throwable error) {
        LOGGER.warn(
                "Rolling conversation summary failed; retaining raw turns: personId={}, errorType={}",
                personId,
                error.getClass().getSimpleName()
        );
    }

    private static void logEpisodeFailure(
            PersonId personId,
            String stage,
            Throwable error
    ) {
        LOGGER.warn(
                "Conversation episode {} failed; continuing without episode update: personId={}, errorType={}",
                stage,
                personId,
                error.getClass().getSimpleName()
        );
    }
}
