package com.laishengkai.digitalperson.infrastructure.conversation;

import com.laishengkai.digitalperson.conversation.ConversationSummarySnapshot;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;
import com.laishengkai.digitalperson.person.PersonId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

class SummaryAwareRecentConversationGatewayTest {

    @Test
    void appendsSyntheticSummaryAfterRawTurnsForCharacterBudgetRetention() {
        PersonId personId = PersonId.random();
        Instant now = Instant.parse("2026-07-25T03:00:00Z");
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
                        ))
                );

        List<ConversationTurnSnapshot> result = gateway.retrieve(
                new RecentConversationQuery(personId, "", 12)
        ).toCompletableFuture().join();

        assertThat(result)
                .extracting(ConversationTurnSnapshot::role)
                .containsExactly(
                        ConversationTurnSnapshot.Role.USER,
                        ConversationTurnSnapshot.Role.PERSON,
                        ConversationTurnSnapshot.Role.SUMMARY
                );
        assertThat(result.getLast().text()).isEqualTo("较早对话摘要");
        assertThat(result.getLast().occurredAt()).isEqualTo(now.minusSeconds(30));
    }
}
