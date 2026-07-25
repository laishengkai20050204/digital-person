package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationSummarySnapshot;
import com.laishengkai.digitalperson.conversation.ConversationSummaryStore;
import com.laishengkai.digitalperson.conversation.ConversationSummaryWorkItem;
import com.laishengkai.digitalperson.dialogue.ConversationSummaryModel;
import com.laishengkai.digitalperson.person.PersonId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Summarizes older raw turns in bounded batches without blocking normal dialogue on failure. */
public final class ConversationSummaryService {
    private static final Logger LOGGER = LoggerFactory.getLogger(
            ConversationSummaryService.class
    );

    private final ConversationSummaryStore summaryStore;
    private final ConversationSummaryModel summaryModel;
    private final int recentTurnsToKeep;
    private final int batchTurns;

    public ConversationSummaryService(
            ConversationSummaryStore summaryStore,
            ConversationSummaryModel summaryModel,
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
            logFailure(requestedPersonId, error);
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
            )).thenAccept(saved -> {
                if (Boolean.TRUE.equals(saved)) {
                    LOGGER.info(
                            "Rolling conversation summary updated: personId={}, summarizedTurnCount={}, coveredThroughTurnId={}",
                            requestedPersonId,
                            item.turns().size(),
                            item.coveredThroughTurnId()
                    );
                } else {
                    LOGGER.info(
                            "Rolling conversation summary update lost optimistic race: personId={}, expectedVersion={}",
                            requestedPersonId,
                            item.expectedVersion()
                    );
                }
            });
        }).handle((ignored, failure) -> {
            if (failure != null) {
                logFailure(requestedPersonId, unwrap(failure));
            }
            return null;
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

    private static void logFailure(PersonId personId, Throwable error) {
        LOGGER.warn(
                "Rolling conversation summary failed; retaining raw turns: personId={}, errorType={}",
                personId,
                error.getClass().getSimpleName()
        );
    }
}
