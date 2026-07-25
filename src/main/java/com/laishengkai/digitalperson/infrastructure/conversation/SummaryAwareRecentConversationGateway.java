package com.laishengkai.digitalperson.infrastructure.conversation;

import com.laishengkai.digitalperson.conversation.ConversationEpisodeGateway;
import com.laishengkai.digitalperson.conversation.ConversationEpisodeSnapshot;
import com.laishengkai.digitalperson.conversation.ConversationSummaryGateway;
import com.laishengkai.digitalperson.conversation.ConversationSummarySnapshot;
import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Adds persisted older-turn summaries and relevant event memories to recent raw history. */
public final class SummaryAwareRecentConversationGateway implements RecentConversationGateway {
    private final RecentConversationGateway rawConversationGateway;
    private final ConversationSummaryGateway summaryGateway;
    private final ConversationEpisodeGateway episodeGateway;
    private final int maxEpisodeItems;

    public SummaryAwareRecentConversationGateway(
            RecentConversationGateway rawConversationGateway,
            ConversationSummaryGateway summaryGateway
    ) {
        this(rawConversationGateway, summaryGateway, null, 1);
    }

    public SummaryAwareRecentConversationGateway(
            RecentConversationGateway rawConversationGateway,
            ConversationSummaryGateway summaryGateway,
            ConversationEpisodeGateway episodeGateway,
            int maxEpisodeItems
    ) {
        this.rawConversationGateway = Objects.requireNonNull(
                rawConversationGateway,
                "rawConversationGateway cannot be null"
        );
        this.summaryGateway = Objects.requireNonNull(
                summaryGateway,
                "summaryGateway cannot be null"
        );
        this.episodeGateway = episodeGateway;
        if (maxEpisodeItems <= 0) {
            throw new IllegalArgumentException("maxEpisodeItems must be positive");
        }
        this.maxEpisodeItems = maxEpisodeItems;
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
        CompletionStage<Optional<ConversationSummarySnapshot>> summaryStage =
                Objects.requireNonNull(
                        summaryGateway.retrieve(requested.personId()),
                        "summaryGateway stage cannot be null"
                );
        CompletionStage<List<ConversationEpisodeSnapshot>> episodeStage = episodeGateway == null
                ? CompletableFuture.completedFuture(List.of())
                : Objects.requireNonNull(
                        episodeGateway.retrieve(
                                requested.personId(),
                                requested.relevanceQuery(),
                                maxEpisodeItems
                        ),
                        "episodeGateway stage cannot be null"
                );

        return rawStage.thenCombine(
                episodeStage,
                (rawTurns, episodes) -> new PartialContext(
                        List.copyOf(Objects.requireNonNull(
                                rawTurns,
                                "raw turns cannot be null"
                        )),
                        List.copyOf(Objects.requireNonNull(
                                episodes,
                                "episodes cannot be null"
                        ))
                )
        ).thenCombine(summaryStage, (partial, summary) -> {
            Optional<ConversationSummarySnapshot> safeSummary = Objects.requireNonNull(
                    summary,
                    "summary cannot be null"
            );
            ArrayList<ConversationTurnSnapshot> combined = new ArrayList<>(
                    partial.rawTurns().size()
                            + partial.episodes().size()
                            + (safeSummary.isPresent() ? 1 : 0)
            );
            combined.addAll(partial.rawTurns());
            partial.episodes().forEach(episode -> combined.add(
                    new ConversationTurnSnapshot(
                            ConversationTurnSnapshot.Role.EPISODE,
                            episode.episode().contextText(),
                            episode.endedAt()
                    )
            ));
            safeSummary.ifPresent(value -> combined.add(new ConversationTurnSnapshot(
                    ConversationTurnSnapshot.Role.SUMMARY,
                    value.content(),
                    value.updatedAt()
            )));
            return List.copyOf(combined);
        });
    }

    private record PartialContext(
            List<ConversationTurnSnapshot> rawTurns,
            List<ConversationEpisodeSnapshot> episodes
    ) {
    }
}
