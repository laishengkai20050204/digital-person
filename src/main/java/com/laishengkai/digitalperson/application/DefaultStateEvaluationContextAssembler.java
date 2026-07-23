package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.modelcontext.PersonModelContextSnapshot;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** State-specific projection over the shared person model context assembler. */
public final class DefaultStateEvaluationContextAssembler
        implements StateEvaluationContextAssembler {
    public static final Duration DEFAULT_RECENT_EVENT_WINDOW =
            DefaultPersonModelContextAssembler.DEFAULT_RECENT_EVENT_WINDOW;
    public static final int DEFAULT_MAX_MEMORY_ITEMS = 20;
    public static final int DEFAULT_MAX_CONVERSATION_TURNS = 20;

    private final PersonModelContextAssembler commonAssembler;
    private final int maxMemoryItems;
    private final int maxConversationTurns;

    public DefaultStateEvaluationContextAssembler(
            PersonMemoryGateway memoryGateway,
            RecentConversationGateway conversationGateway
    ) {
        this(
                new DefaultPersonModelContextAssembler(
                        memoryGateway,
                        conversationGateway
                ),
                DEFAULT_MAX_MEMORY_ITEMS,
                DEFAULT_MAX_CONVERSATION_TURNS
        );
    }

    public DefaultStateEvaluationContextAssembler(
            PersonMemoryGateway memoryGateway,
            RecentConversationGateway conversationGateway,
            Duration recentEventWindow,
            int maxMemoryItems,
            int maxConversationTurns
    ) {
        this(
                new DefaultPersonModelContextAssembler(
                        memoryGateway,
                        conversationGateway,
                        recentEventWindow
                ),
                maxMemoryItems,
                maxConversationTurns
        );
    }

    public DefaultStateEvaluationContextAssembler(
            PersonModelContextAssembler commonAssembler
    ) {
        this(
                commonAssembler,
                DEFAULT_MAX_MEMORY_ITEMS,
                DEFAULT_MAX_CONVERSATION_TURNS
        );
    }

    public DefaultStateEvaluationContextAssembler(
            PersonModelContextAssembler commonAssembler,
            int maxMemoryItems,
            int maxConversationTurns
    ) {
        this.commonAssembler = Objects.requireNonNull(
                commonAssembler,
                "commonAssembler cannot be null"
        );
        if (maxMemoryItems <= 0 || maxConversationTurns <= 0) {
            throw new IllegalArgumentException("retrieval limits must be positive");
        }
        this.maxMemoryItems = maxMemoryItems;
        this.maxConversationTurns = maxConversationTurns;
    }

    /** Convenience fallback that preserves behavior before external providers exist. */
    public static DefaultStateEvaluationContextAssembler withoutExternalSources() {
        return new DefaultStateEvaluationContextAssembler(
                DefaultPersonModelContextAssembler.withoutExternalSources()
        );
    }

    @Override
    public CompletionStage<StateEvaluationContext> assemble(
            Person person,
            PersonStateSnapshot currentState,
            StateEvolutionContext currentEvolution,
            PersonEvent newEvent,
            Instant evaluationTime
    ) {
        PersonEvent event = Objects.requireNonNull(
                newEvent,
                "newEvent cannot be null"
        ).copy();
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        PersonModelContextAssemblyRequest request = new PersonModelContextAssemblyRequest(
                Set.of(event.getId()),
                relevanceQuery(event),
                false,
                maxMemoryItems,
                maxConversationTurns
        );
        return commonAssembler.assemble(
                person,
                currentState,
                currentEvolution,
                request,
                now
        ).thenApply(common -> toStateContext(common, event, now));
    }

    private static StateEvaluationContext toStateContext(
            PersonModelContextSnapshot common,
            PersonEvent event,
            Instant evaluationTime
    ) {
        return new StateEvaluationContext(
                common.personId(),
                common.identity(),
                common.personality(),
                common.currentState(),
                common.activeEffects(),
                PersonEventSnapshot.from(
                        PersonEventSnapshot.Owner.PERSON,
                        event,
                        evaluationTime
                ),
                common.activeEvents(),
                common.recentEvents(),
                common.memory(),
                common.recentConversation(),
                common.temporal(),
                evaluationTime
        );
    }

    private static String relevanceQuery(PersonEvent event) {
        return String.join(
                "\n",
                event.getActivityType().name(),
                event.getTitle(),
                event.getLocation(),
                String.join(", ", event.getParticipants()),
                event.getNotes()
        ).strip();
    }
}
