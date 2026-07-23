package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.activity.PersonActivityDecisionContext;
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

/** Default activity-decision context assembler backed by memory and conversation ports. */
public final class DefaultPersonActivityDecisionContextAssembler
        implements PersonActivityDecisionContextAssembler {
    public static final Duration DEFAULT_RECENT_EVENT_WINDOW = Duration.ofHours(24);
    public static final int DEFAULT_MEMORY_LIMIT = 30;
    public static final int DEFAULT_CONVERSATION_LIMIT = 20;

    private final PersonMemoryGateway memoryGateway;
    private final RecentConversationGateway conversationGateway;
    private final Duration recentEventWindow;
    private final int memoryLimit;
    private final int conversationLimit;

    public DefaultPersonActivityDecisionContextAssembler(
            PersonMemoryGateway memoryGateway,
            RecentConversationGateway conversationGateway
    ) {
        this(
                memoryGateway,
                conversationGateway,
                DEFAULT_RECENT_EVENT_WINDOW,
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
        if (memoryLimit <= 0 || conversationLimit <= 0) {
            throw new IllegalArgumentException("context retrieval limits must be positive");
        }
        this.memoryLimit = memoryLimit;
        this.conversationLimit = conversationLimit;
    }

    public static DefaultPersonActivityDecisionContextAssembler withoutExternalSources() {
        return new DefaultPersonActivityDecisionContextAssembler(
                query -> CompletableFuture.completedFuture(PersonMemoryContext.disabled()),
                query -> CompletableFuture.completedFuture(List.of())
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
        Person source = Objects.requireNonNull(person, "person cannot be null").copy();
        PersonStateSnapshot state = Objects.requireNonNull(
                currentState,
                "currentState cannot be null"
        );
        StateEvolutionContext evolution = Objects.requireNonNull(
                currentEvolution,
                "currentEvolution cannot be null"
        );
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );
        String normalizedObservation = Objects.requireNonNullElse(
                observation,
                ""
        ).strip();

        List<PersonEventSnapshot> activeEvents = activeEvents(source, now);
        Set<String> activeEventIds = new HashSet<>();
        activeEvents.forEach(event -> activeEventIds.add(event.eventId()));
        List<PersonEventSnapshot> recentEvents = recentEvents(
                source,
                now,
                activeEventIds
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
        String relevanceQuery = relevanceQuery(
                normalizedObservation,
                activeEvents,
                recentEvents
        );

        CompletionStage<PersonMemoryContext> memoryStage = Objects.requireNonNull(
                memoryGateway.retrieve(new PersonMemoryQuery(
                        source.getId(),
                        relevanceQuery,
                        EnumSet.allOf(MemorySection.class),
                        memoryLimit
                )),
                "memoryGateway stage cannot be null"
        );
        CompletionStage<List<ConversationTurnSnapshot>> conversationStage =
                Objects.requireNonNull(
                        conversationGateway.retrieve(new RecentConversationQuery(
                                source.getId(),
                                relevanceQuery,
                                conversationLimit
                        )),
                        "conversationGateway stage cannot be null"
                );

        return memoryStage.thenCombine(conversationStage, (memory, conversation) ->
                new PersonActivityDecisionContext(
                        source.getId(),
                        PersonIdentitySnapshot.from(source.getIdentity(), now),
                        PersonalitySnapshot.from(source.getPersonality()),
                        state,
                        activeEffects,
                        activeEvents,
                        recentEvents,
                        Objects.requireNonNull(memory, "memory result cannot be null"),
                        List.copyOf(Objects.requireNonNull(
                                conversation,
                                "conversation result cannot be null"
                        )),
                        normalizedObservation,
                        now
                )
        );
    }

    private static List<PersonEventSnapshot> activeEvents(Person person, Instant now) {
        List<PersonEventSnapshot> result = new ArrayList<>();
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.PERSON,
                person.getCurrentPersonEvents(now),
                Set.of()
        );
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.USER,
                person.getCurrentUserEvents(now),
                Set.of()
        );
        return sorted(result);
    }

    private List<PersonEventSnapshot> recentEvents(
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
        return sorted(result);
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

    private static List<PersonEventSnapshot> sorted(List<PersonEventSnapshot> values) {
        return values.stream()
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

    private static String relevanceQuery(
            String observation,
            List<PersonEventSnapshot> activeEvents,
            List<PersonEventSnapshot> recentEvents
    ) {
        List<String> parts = new ArrayList<>();
        if (!observation.isBlank()) {
            parts.add(observation);
        }
        activeEvents.forEach(event -> {
            parts.add(event.owner() + " " + event.activityType() + " " + event.title());
            if (!event.notes().isBlank()) {
                parts.add(event.notes());
            }
        });
        recentEvents.stream().skip(Math.max(0, recentEvents.size() - 5L)).forEach(event ->
                parts.add(event.owner() + " " + event.activityType() + " " + event.title())
        );
        return String.join("\n", parts);
    }
}
