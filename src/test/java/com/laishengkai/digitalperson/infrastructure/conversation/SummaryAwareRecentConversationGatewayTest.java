package com.laishengkai.digitalperson.infrastructure.conversation;

import com.laishengkai.digitalperson.conversation.ConversationEpisodeDraft;
import com.laishengkai.digitalperson.conversation.ConversationEpisodeSnapshot;
import com.laishengkai.digitalperson.conversation.ConversationSummarySnapshot;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryAwareRecentConversationGatewayTest {

    @Test
    void appendsEpisodesAndSummaryAfterRawTurnsForCharacterBudgetRetention() {
        PersonId personId = PersonId.random();
        Instant now = Instant.parse("2026-07-25T03:00:00Z");
        AtomicReference<String> episodeQuery = new AtomicReference<>();
        SummaryAwareRecentConversationGateway gateway =
                new SummaryAwareRecentConversationGateway(
                        query -> CompletableFuture.completedFuture(List.of(
                                new ConversationTurnSnapshot(
                                        ConversationTurnSnapshot.Role.USER,
                                        "最近用户消息",
                                        now.minusSeconds(20)
                                ),
                                new ConversationTurnSnapshot(
                                        ConversationTurnSnapshot.Role.PERSON,
                                        "最近人物回复",
                                        now.minusSeconds(10)
                                )
                        )),
                        requested -> CompletableFuture.completedFuture(Optional.of(
                                new ConversationSummarySnapshot(
                                        "较早对话摘要",
                                        8,
                                        8,
                                        0,
                                        now.minusSeconds(60),
                                        now.minusSeconds(30)
                                )
                        )),
                        (requestedPersonId, relevanceQuery, maxItems) -> {
                            episodeQuery.set(relevanceQuery);
                            assertThat(requestedPersonId).isEqualTo(personId);
                            assertThat(maxItems).isEqualTo(3);
                            return CompletableFuture.completedFuture(List.of(
                                    new ConversationEpisodeSnapshot(
                                            9,
                                            new ConversationEpisodeDraft(
                                                    "用户调整相处方式",
                                                    "用户在一次冲突后决定简短表达感受。",
                                                    "CONFLICT",
                                                    List.of("用户", "游戏搭子"),
                                                    List.of("失落"),
                                                    "用户决定观察后续行动。",
                                                    0.8
                                            ),
                                            1,
                                            8,
                                            now.minusSeconds(120),
                                            now.minusSeconds(90),
                                            now.minusSeconds(80),
                                            0.9
                                    )
                            ));
                        },
                        3
                );

        List<ConversationTurnSnapshot> result = gateway.retrieve(
                new RecentConversationQuery(personId, "上次和游戏搭子的矛盾", 12)
        ).toCompletableFuture().join();

        assertThat(episodeQuery).hasValue("上次和游戏搭子的矛盾");
        assertThat(result)
                .extracting(ConversationTurnSnapshot::role)
                .containsExactly(
                        ConversationTurnSnapshot.Role.USER,
                        ConversationTurnSnapshot.Role.PERSON,
                        ConversationTurnSnapshot.Role.EPISODE,
                        ConversationTurnSnapshot.Role.SUMMARY
                );
        assertThat(result.get(2).text())
                .contains("用户调整相处方式")
                .contains("用户决定观察后续行动");
        assertThat(result.getLast().text()).isEqualTo("较早对话摘要");
        assertThat(result.getLast().occurredAt()).isEqualTo(now.minusSeconds(30));
    }

    @Test
    void preservesSummaryOnlyCompatibilityConstructor() {
        PersonId personId = PersonId.random();
        Instant now = Instant.parse("2026-07-25T03:00:00Z");
        SummaryAwareRecentConversationGateway gateway =
                new SummaryAwareRecentConversationGateway(
                        query -> CompletableFuture.completedFuture(List.of()),
                        requested -> CompletableFuture.completedFuture(Optional.of(
                                new ConversationSummarySnapshot(
                                        "仅摘要",
                                        8,
                                        8,
                                        0,
                                        now,
                                        now
                                )
                        ))
                );

        List<ConversationTurnSnapshot> result = gateway.retrieve(
                new RecentConversationQuery(personId, "", 12)
        ).toCompletableFuture().join();

        assertThat(result)
                .extracting(ConversationTurnSnapshot::role)
                .containsExactly(ConversationTurnSnapshot.Role.SUMMARY);
    }
}
