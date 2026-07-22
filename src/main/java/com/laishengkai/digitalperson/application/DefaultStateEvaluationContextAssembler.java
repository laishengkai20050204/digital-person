package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateEvaluationContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Default application-layer context assembly using memory and conversation ports. */
public final class DefaultStateEvaluationContextAssembler
        implements StateEvaluationContextAssembler {

    public static final Duration DEFAULT_RECENT_EVENT_WINDOW = Duration.ofHours(24);
    public static final int DEFAULT_MAX_MEMORY_ITEMS = 20;
    public static final int DEFAULT_MAX_CONVERSATION_TURNS = 20;

    private final PersonMemoryGateway memoryGateway;
    private final RecentConversationGateway conversationGateway;
    private final Duration recentEventWindow;
    private final int maxMemoryItems;
    private final int maxConversationTurns;

    public DefaultStateEvaluationContextAssembler(
            PersonMemoryGateway memoryGateway,
            RecentConversationGateway conversationGateway
    ) {
        this(
                memoryGateway,
                conversationGateway,
                DEFAULT_RECENT_EVENT_WINDOW,
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
        this.memoryGateway = Objects.requireNonNull(
                memoryGateway,
                "memoryGateway cannot be null"
        );
        this.conversationGateway = Objects.requireNonNull(
                conversationGateway,
                "conversationGateway cannot be null"
        );
        this.recentEventWindow = Objects.requireNonNull(
                recentEventWindow,
                "recentEventWindow cannot be null"
        );
        if (recentEventWindow.isNegative() || recentEventWindow.isZero()) {
            throw new IllegalArgumentException("recentEventWindow must be positive");
        }
        if (maxMemoryItems <= 0 || maxConversationTurns <= 0) {
            throw new IllegalArgumentException("retrieval limits must be positive");
        }
        this.maxMemoryItems = maxMemoryItems;
        this.maxConversationTurns = maxConversationTurns;
    }

    /** Convenience fallback that preserves behavior before external providers exist. */
    public static DefaultStateEvaluationContextAssembler withoutExternalSources() {
        return new DefaultStateEvaluationContextAssembler(
                query -> CompletableFuture.completedFuture(
                        PersonMemoryContext.disabled()
                ),
                query -> CompletableFuture.completedFuture(List.of())
        );
    }

    @Override
    public CompletionStage<StateEvaluationContext> assemble(
            Person person,
            PersonStateSnapshot currentState,
            PersonEvent newEvent,
            Instant evaluationTime
    ) {
        Person source = Objects.requireNonNull(person, "person cannot be null");
        PersonStateSnapshot state = Objects.requireNonNull(
                currentState,
                "currentState cannot be null"
        );
        PersonEvent event = Objects.requireNonNull(
                newEvent,
                "newEvent cannot be null"
        ).copy();
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        String relevanceQuery = relevanceQuery(event);

        CompletionStage<PersonMemoryContext> memoryStage = Objects.requireNonNull(
                memoryGateway.retrieve(new PersonMemoryQuery(
                        source.getId(),
                        relevanceQuery,
                        EnumSet.allOf(MemorySection.class),
                        maxMemoryItems
                )),
                "memoryGateway stage cannot be null"
        );
        CompletionStage<List<ConversationTurnSnapshot>> conversationStage =
                Objects.requireNonNull(
                        conversationGateway.retrieve(new RecentConversationQuery(
                                source.getId(),
                                relevanceQuery,
                                maxConversationTurns
                        )),
                        "conversationGateway stage cannot be null"
                );

        return memoryStage.thenCombine(conversationStage, (memory, conversation) ->
                new StateEvaluationContext(
                        source.getId(),
                        PersonalitySnapshot.from(source.getPersonality()),
                        state,
                        PersonEventSnapshot.from(PersonEventSnapshot.Owner.PERSON, event),
                        snapshotActiveEvents(source, now),
                        snapshotRecentEvents(source, now),
                        Objects.requireNonNull(memory, "memory result cannot be null"),
                        List.copyOf(Objects.requireNonNull(
                                conversation,
                                "conversation result cannot be null"
                        )),
                        now
                )
        );
    }

    private List<PersonEventSnapshot> snapshotActiveEvents(Person person, Instant now) {
        List<PersonEventSnapshot> result = new ArrayList<>();
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.PERSON,
                person.getCurrentPersonEvents(now)
        );
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.USER,
                person.getCurrentUserEvents(now)
        );
        return List.copyOf(result);
    }

    private List<PersonEventSnapshot> snapshotRecentEvents(Person person, Instant now) {
        List<PersonEventSnapshot> result = new ArrayList<>();
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.PERSON,
                person.getRecentPersonEvents(now, recentEventWindow)
        );
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.USER,
                person.getRecentUserEvents(now, recentEventWindow)
        );
        return List.copyOf(result);
    }

    private static void addSnapshots(
            List<PersonEventSnapshot> target,
            PersonEventSnapshot.Owner owner,
            List<PersonEvent> events
    ) {
        events.stream()
                .map(event -> PersonEventSnapshot.from(owner, event))
                .forEach(target::add);
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
