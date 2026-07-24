package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.ActivityPhysiologySnapshot;
import com.laishengkai.digitalperson.activity.PersonActivityDecisionContext;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.modelcontext.PersonModelContextSnapshot;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Activity-specific projection over the shared person model context assembler. */
public final class DefaultPersonActivityDecisionContextAssembler
        implements PersonActivityDecisionContextAssembler {
    public static final Duration DEFAULT_RECENT_EVENT_WINDOW =
            DefaultPersonModelContextAssembler.DEFAULT_RECENT_EVENT_WINDOW;
    public static final int DEFAULT_MEMORY_LIMIT = 30;
    public static final int DEFAULT_CONVERSATION_LIMIT = 20;

    private final PersonModelContextAssembler commonAssembler;
    private final int memoryLimit;
    private final int conversationLimit;

    public DefaultPersonActivityDecisionContextAssembler(
            PersonMemoryGateway memoryGateway,
            RecentConversationGateway conversationGateway
    ) {
        this(
                new DefaultPersonModelContextAssembler(
                        memoryGateway,
                        conversationGateway
                ),
                DEFAULT_MEMORY_LIMIT,
                DEFAULT_CONVERSATION_LIMIT
        );
    }

    public DefaultPersonActivityDecisionContextAssembler(
            PersonMemoryGateway memoryGateway,
            RecentConversationGateway conversationGateway,
            Duration recentEventWindow,
            int memoryLimit,
            int conversationLimit
    ) {
        this(
                new DefaultPersonModelContextAssembler(
                        memoryGateway,
                        conversationGateway,
                        recentEventWindow
                ),
                memoryLimit,
                conversationLimit
        );
    }

    public DefaultPersonActivityDecisionContextAssembler(
            PersonModelContextAssembler commonAssembler
    ) {
        this(commonAssembler, DEFAULT_MEMORY_LIMIT, DEFAULT_CONVERSATION_LIMIT);
    }

    public DefaultPersonActivityDecisionContextAssembler(
            PersonModelContextAssembler commonAssembler,
            int memoryLimit,
            int conversationLimit
    ) {
        this.commonAssembler = Objects.requireNonNull(
                commonAssembler,
                "commonAssembler cannot be null"
        );
        if (memoryLimit <= 0 || conversationLimit <= 0) {
            throw new IllegalArgumentException("context retrieval limits must be positive");
        }
        this.memoryLimit = memoryLimit;
        this.conversationLimit = conversationLimit;
    }

    public static DefaultPersonActivityDecisionContextAssembler withoutExternalSources() {
        return new DefaultPersonActivityDecisionContextAssembler(
                DefaultPersonModelContextAssembler.withoutExternalSources()
        );
    }

    @Override
    public CompletionStage<PersonActivityDecisionContext> assemble(
            Person person,
            PersonStateSnapshot currentState,
            StateEvolutionContext currentEvolution,
            String observation,
            Instant evaluationTime
    ) {
        String normalizedObservation = Objects.requireNonNullElse(
                observation,
                ""
        ).strip();
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        PersonModelContextAssemblyRequest request = new PersonModelContextAssemblyRequest(
                Set.of(),
                normalizedObservation,
                true,
                memoryLimit,
                conversationLimit
        );
        ActivityPhysiologySnapshot physiology = ActivityPhysiologySnapshot.from(
                person.getPersonTimeline().getAll(),
                now
        );
        return commonAssembler.assemble(
                person,
                currentState,
                currentEvolution,
                request,
                now
        ).thenApply(common -> toActivityContext(
                common,
                physiology,
                normalizedObservation,
                now
        ));
    }

    private static PersonActivityDecisionContext toActivityContext(
            PersonModelContextSnapshot common,
            ActivityPhysiologySnapshot physiology,
            String observation,
            Instant evaluationTime
    ) {
        return new PersonActivityDecisionContext(
                common.personId(),
                common.identity(),
                common.personality(),
                common.currentState(),
                common.activeEffects(),
                common.activeEvents(),
                common.recentEvents(),
                physiology,
                common.memory(),
                common.recentConversation(),
                observation,
                common.temporal(),
                evaluationTime
        );
    }
}
