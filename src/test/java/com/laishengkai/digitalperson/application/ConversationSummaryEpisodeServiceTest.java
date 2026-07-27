package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationEpisodeDraft;
import com.laishengkai.digitalperson.conversation.ConversationEpisodeSnapshot;
import com.laishengkai.digitalperson.conversation.ConversationEpisodeStore;
import com.laishengkai.digitalperson.conversation.ConversationSummarySnapshot;
import com.laishengkai.digitalperson.conversation.ConversationSummaryStore;
import com.laishengkai.digitalperson.conversation.ConversationSummaryWorkItem;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSummaryEpisodeServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");

    @Test
    void persistsEpisodesBeforeTheSummaryCommit() {
        PersonId personId = PersonId.random();
        ConversationSummaryWorkItem work = workItem();
        AtomicBoolean summaryCommitted = new AtomicBoolean();
        AtomicReference<List<ConversationEpisodeDraft>> storedEpisodes =
                new AtomicReference<>();

        ConversationSummaryStore summaryStore = summaryStore(
                work,
                summaryCommitted,
                true
        );
        ConversationEpisodeStore episodeStore = new ConversationEpisodeStore() {
            @Override
            public CompletableFuture<Integer> saveAll(
                    PersonId requested,
                    ConversationSummaryWorkItem requestedWork,
                    List<ConversationEpisodeDraft> episodes,
                    Instant extractedAt
            ) {
                assertThat(summaryCommitted).isFalse();
                assertThat(requested).isEqualTo(personId);
                assertThat(requestedWork).isEqualTo(work);
                storedEpisodes.set(List.copyOf(episodes));
                return CompletableFuture.completedFuture(episodes.size());
            }

            @Override
            public CompletableFuture<List<ConversationEpisodeSnapshot>> retrieve(
                    PersonId requested,
                    String relevanceQuery,
                    int maxItems
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        ConversationEpisodeDraft episode = episode();
        ConversationSummaryService service = new ConversationSummaryService(
                summaryStore,
                (existing, turns, zone) -> CompletableFuture.completedFuture("新摘要"),
                episodeStore,
                (turns, zone) -> CompletableFuture.completedFuture(List.of(episode)),
                12,
                8
        );

        service.summarizeIfNeeded(personId, ZoneId.of("Asia/Shanghai"), NOW)
                .toCompletableFuture()
                .join();

        assertThat(summaryCommitted).isTrue();
        assertThat(storedEpisodes.get()).containsExactly(episode);
    }

    @Test
    void keepsIdempotentEpisodePersistenceWhenTheSummaryLosesTheOptimisticRace() {
        PersonId personId = PersonId.random();
        ConversationSummaryWorkItem work = workItem();
        AtomicBoolean summaryCommitted = new AtomicBoolean();
        AtomicBoolean episodeStoreCalled = new AtomicBoolean();

        ConversationEpisodeStore episodeStore = successfulEpisodeStore(episodeStoreCalled);
        ConversationSummaryService service = new ConversationSummaryService(
                summaryStore(work, summaryCommitted, false),
                (existing, turns, zone) -> CompletableFuture.completedFuture("过期摘要"),
                episodeStore,
                (turns, zone) -> CompletableFuture.completedFuture(List.of(episode())),
                12,
                8
        );

        service.summarizeIfNeeded(personId, ZoneId.of("UTC"), NOW)
                .toCompletableFuture()
                .join();

        assertThat(summaryCommitted).isFalse();
        assertThat(episodeStoreCalled).isTrue();
    }

    @Test
    void retainsTheSummaryBatchWhenEpisodeExtractionFails() {
        PersonId personId = PersonId.random();
        ConversationSummaryWorkItem work = workItem();
        AtomicBoolean summaryCommitted = new AtomicBoolean();
        AtomicBoolean episodeStoreCalled = new AtomicBoolean();

        ConversationSummaryService service = new ConversationSummaryService(
                summaryStore(work, summaryCommitted, true),
                (existing, turns, zone) -> CompletableFuture.completedFuture("不应提交的摘要"),
                successfulEpisodeStore(episodeStoreCalled),
                (turns, zone) -> CompletableFuture.failedFuture(
                        new RuntimeException("episode model unavailable")
                ),
                12,
                8
        );

        service.summarizeIfNeeded(personId, ZoneId.of("UTC"), NOW)
                .toCompletableFuture()
                .join();

        assertThat(summaryCommitted).isFalse();
        assertThat(episodeStoreCalled).isFalse();
    }

    @Test
    void retainsTheSummaryBatchWhenEpisodePersistenceFails() {
        PersonId personId = PersonId.random();
        ConversationSummaryWorkItem work = workItem();
        AtomicBoolean summaryCommitted = new AtomicBoolean();
        AtomicBoolean episodeStoreCalled = new AtomicBoolean();

        ConversationEpisodeStore episodeStore = new ConversationEpisodeStore() {
            @Override
            public CompletableFuture<Integer> saveAll(
                    PersonId requested,
                    ConversationSummaryWorkItem requestedWork,
                    List<ConversationEpisodeDraft> episodes,
                    Instant extractedAt
            ) {
                episodeStoreCalled.set(true);
                return CompletableFuture.failedFuture(
                        new RuntimeException("episode database unavailable")
                );
            }

            @Override
            public CompletableFuture<List<ConversationEpisodeSnapshot>> retrieve(
                    PersonId requested,
                    String relevanceQuery,
                    int maxItems
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
        ConversationSummaryService service = new ConversationSummaryService(
                summaryStore(work, summaryCommitted, true),
                (existing, turns, zone) -> CompletableFuture.completedFuture("不应提交的摘要"),
                episodeStore,
                (turns, zone) -> CompletableFuture.completedFuture(List.of(episode())),
                12,
                8
        );

        service.summarizeIfNeeded(personId, ZoneId.of("UTC"), NOW)
                .toCompletableFuture()
                .join();

        assertThat(episodeStoreCalled).isTrue();
        assertThat(summaryCommitted).isFalse();
    }

    @Test
    void advancesTheSummaryWhenTheModelExplicitlySubmitsNoEpisodes() {
        PersonId personId = PersonId.random();
        ConversationSummaryWorkItem work = workItem();
        AtomicBoolean summaryCommitted = new AtomicBoolean();
        AtomicBoolean episodeStoreCalled = new AtomicBoolean();

        ConversationSummaryService service = new ConversationSummaryService(
                summaryStore(work, summaryCommitted, true),
                (existing, turns, zone) -> CompletableFuture.completedFuture("无事件摘要"),
                successfulEpisodeStore(episodeStoreCalled),
                (turns, zone) -> CompletableFuture.completedFuture(List.of()),
                12,
                8
        );

        service.summarizeIfNeeded(personId, ZoneId.of("UTC"), NOW)
                .toCompletableFuture()
                .join();

        assertThat(summaryCommitted).isTrue();
        assertThat(episodeStoreCalled).isFalse();
    }

    private static ConversationEpisodeStore successfulEpisodeStore(AtomicBoolean called) {
        return new ConversationEpisodeStore() {
            @Override
            public CompletableFuture<Integer> saveAll(
                    PersonId requested,
                    ConversationSummaryWorkItem requestedWork,
                    List<ConversationEpisodeDraft> episodes,
                    Instant extractedAt
            ) {
                called.set(true);
                return CompletableFuture.completedFuture(episodes.size());
            }

            @Override
            public CompletableFuture<List<ConversationEpisodeSnapshot>> retrieve(
                    PersonId requested,
                    String relevanceQuery,
                    int maxItems
            ) {
                return CompletableFuture.completedFuture(List.of());
            }
        };
    }

    private static ConversationSummaryStore summaryStore(
            ConversationSummaryWorkItem work,
            AtomicBoolean committed,
            boolean saveResult
    ) {
        return new ConversationSummaryStore() {
            @Override
            public CompletableFuture<Optional<ConversationSummarySnapshot>> retrieve(
                    PersonId personId
            ) {
                return CompletableFuture.completedFuture(Optional.empty());
            }

            @Override
            public CompletableFuture<Optional<ConversationSummaryWorkItem>> findWork(
                    PersonId personId,
                    int recentTurnsToKeep,
                    int batchTurns
            ) {
                return CompletableFuture.completedFuture(Optional.of(work));
            }

            @Override
            public CompletableFuture<Boolean> save(
                    PersonId personId,
                    ConversationSummaryWorkItem workItem,
                    String summary,
                    Instant summarizedAt
            ) {
                if (saveResult) {
                    committed.set(true);
                }
                return CompletableFuture.completedFuture(saveResult);
            }
        };
    }

    private static ConversationSummaryWorkItem workItem() {
        return new ConversationSummaryWorkItem(
                Optional.empty(),
                List.of(
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.USER,
                                "用户描述了一件重要事情。",
                                NOW.minusSeconds(120)
                        ),
                        new ConversationTurnSnapshot(
                                ConversationTurnSnapshot.Role.PERSON,
                                "人物帮助用户形成了后续决定。",
                                NOW.minusSeconds(60)
                        )
                ),
                101,
                108
        );
    }

    private static ConversationEpisodeDraft episode() {
        return new ConversationEpisodeDraft(
                "用户形成后续决定",
                "用户描述了一件重要事情，并在讨论后形成了明确决定。",
                "PLAN",
                List.of("用户", "人物"),
                List.of("释然"),
                "用户决定按新的方式处理。",
                0.75
        );
    }
}
