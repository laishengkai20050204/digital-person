package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonIdentitySnapshot;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.ActiveStateEffectSnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import com.laishengkai.digitalperson.state.StateEvaluationContext;
import com.laishengkai.digitalperson.state.StateEvolutionContext;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
            StateEvolutionContext currentEvolution,
            PersonEvent newEvent,
            Instant evaluationTime
    ) {
        Person source = Objects.requireNonNull(person, "person cannot be null");
        PersonStateSnapshot state = Objects.requireNonNull(
                currentState,
                "currentState cannot be null"
        );
        StateEvolutionContext evolution = Objects.requireNonNull(
                currentEvolution,
                "currentEvolution cannot be null"
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
        List<PersonEventSnapshot> activeEvents = snapshotActiveEvents(
                source,
                now,
                event.getId()
        );
        Set<String> recentExclusions = new HashSet<>();
        recentExclusions.add(event.getId().toString());
        activeEvents.forEach(active -> recentExclusions.add(active.eventId()));
        List<PersonEventSnapshot> recentEvents = snapshotRecentEvents(
                source,
                now,
                recentExclusions
        );
        Map<EventId, Instant> eventEndTimes = eventEndTimes(source);
        List<ActiveStateEffectSnapshot> activeEffects = evolution.effects().values().stream()
                .filter(effect -> effect.isActiveAt(now, eventEndTimes))
                .sorted(Comparator
                        .comparing(RegisteredStateEffect::startsAt)
                        .thenComparing(RegisteredStateEffect::type)
                        .thenComparing(RegisteredStateEffect::cause))
                .map(effect -> ActiveStateEffectSnapshot.from(effect, eventEndTimes))
                .toList();

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
                        PersonIdentitySnapshot.from(source.getIdentity(), now),
                        PersonalitySnapshot.from(source.getPersonality()),
                        state,
                        activeEffects,
                        PersonEventSnapshot.from(PersonEventSnapshot.Owner.PERSON, event),
                        activeEvents,
                        recentEvents,
                        Objects.requireNonNull(memory, "memory result cannot be null"),
                        List.copyOf(Objects.requireNonNull(
                                conversation,
                                "conversation result cannot be null"
                        )),
                        now
                )
        );
    }

    private List<PersonEventSnapshot> snapshotActiveEvents(
            Person person,
            Instant now,
            EventId newEventId
    ) {
        List<PersonEventSnapshot> result = new ArrayList<>();
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.PERSON,
                person.getCurrentPersonEvents(now),
                Set.of(newEventId.toString())
        );
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.USER,
                person.getCurrentUserEvents(now),
                Set.of(newEventId.toString())
        );
        return sortedSnapshots(result);
    }

    private List<PersonEventSnapshot> snapshotRecentEvents(
            Person person,
            Instant now,
            Set<String> excludedEventIds
    ) {
        List<PersonEventSnapshot> result = new ArrayList<>();
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.PERSON,
                person.getRecentPersonEvents(now, recentEventWindow),
                excludedEventIds
        );
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.USER,
                person.getRecentUserEvents(now, recentEventWindow),
                excludedEventIds
        );
        return sortedSnapshots(result);
    }

    private static void addSnapshots(
            List<PersonEventSnapshot> target,
            PersonEventSnapshot.Owner owner,
            List<PersonEvent> events,
            Set<String> excludedEventIds
    ) {
        events.stream()
                .filter(event -> !excludedEventIds.contains(event.getId().toString()))
                .map(event -> PersonEventSnapshot.from(owner, event))
                .forEach(target::add);
    }

    private static List<PersonEventSnapshot> sortedSnapshots(
            List<PersonEventSnapshot> snapshots
    ) {
        return snapshots.stream()
                .sorted(Comparator
                        .comparing(PersonEventSnapshot::startTime)
                        .thenComparing(PersonEventSnapshot::owner)
                        .thenComparing(PersonEventSnapshot::eventId))
                .toList();
    }

    private static Map<EventId, Instant> eventEndTimes(Person person) {
        Map<EventId, Instant> endTimes = new HashMap<>();
        person.getPersonTimeline().getAll().forEach(event ->
                event.getEndTime().ifPresent(endTime ->
                        endTimes.put(event.getId(), endTime)
                )
        );
        return Map.copyOf(endTimes);
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
