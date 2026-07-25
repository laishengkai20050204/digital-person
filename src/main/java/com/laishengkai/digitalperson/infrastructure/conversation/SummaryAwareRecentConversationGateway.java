package com.laishengkai.digitalperson.infrastructure.conversation;

import com.laishengkai.digitalperson.conversation.ConversationSummaryGateway;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Adds the persisted older-turn summary to the raw recent-turn retrieval result. */
public final class SummaryAwareRecentConversationGateway implements RecentConversationGateway {
    private final RecentConversationGateway rawConversationGateway;
    private final ConversationSummaryGateway summaryGateway;

    public SummaryAwareRecentConversationGateway(
            RecentConversationGateway rawConversationGateway,
            ConversationSummaryGateway summaryGateway
    ) {
        this.rawConversationGateway = Objects.requireNonNull(
                rawConversationGateway,
                "rawConversationGateway cannot be null"
        );
        this.summaryGateway = Objects.requireNonNull(
                summaryGateway,
                "summaryGateway cannot be null"
        );
    }

    @Override
    public CompletionStage<List<ConversationTurnSnapshot>> retrieve(
            RecentConversationQuery query
    ) {
        RecentConversationQuery requested = Objects.requireNonNull(
                query,
                "query cannot be null"
        );
        CompletionStage<List<ConversationTurnSnapshot>> rawStage = Objects.requireNonNull(
                rawConversationGateway.retrieve(requested),
                "rawConversationGateway stage cannot be null"
        );
        var summaryStage = Objects.requireNonNull(
                summaryGateway.retrieve(requested.personId()),
                "summaryGateway stage cannot be null"
        );

        return rawStage.thenCombine(summaryStage, (rawTurns, summary) -> {
            List<ConversationTurnSnapshot> safeRawTurns = List.copyOf(
                    Objects.requireNonNull(rawTurns, "raw turns cannot be null")
            );
            ArrayList<ConversationTurnSnapshot> combined = new ArrayList<>(
                    safeRawTurns.size() + (summary.isPresent() ? 1 : 0)
            );
            combined.addAll(safeRawTurns);
            summary.ifPresent(value -> combined.add(new ConversationTurnSnapshot(
                    ConversationTurnSnapshot.Role.SUMMARY,
                    value.content(),
                    value.updatedAt()
            )));
            return List.copyOf(combined);
        });
    }
}
