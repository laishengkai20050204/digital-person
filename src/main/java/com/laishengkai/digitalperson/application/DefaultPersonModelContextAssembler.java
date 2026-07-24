package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationGateway;
import com.laishengkai.digitalperson.conversation.RecentConversationQuery;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.memory.PersonMemoryGateway;
import com.laishengkai.digitalperson.memory.PersonMemoryQuery;
import com.laishengkai.digitalperson.modelcontext.PersonModelContextSnapshot;
import com.laishengkai.digitalperson.modelcontext.TemporalContextSnapshot;
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

/** Default reusable extraction of identity, state, effects, events, memory and time. */
public final class DefaultPersonModelContextAssembler
        implements PersonModelContextAssembler {
    public static final Duration DEFAULT_RECENT_EVENT_WINDOW = Duration.ofHours(24);
    public static final int DEFAULT_MAX_MEMORY_CHARACTERS = 12_000;
    public static final int DEFAULT_MAX_CONVERSATION_CHARACTERS = 12_000;
    public static final int DEFAULT_MAX_RELEVANCE_QUERY_CHARACTERS = 4_000;

    private final PersonMemoryGateway memoryGateway;
    private final RecentConversationGateway conversationGateway;
    private final Duration recentEventWindow;
    private final int maxMemoryCharacters;
    private final int maxConversationCharacters;
    private final int maxRelevanceQueryCharacters;

    public DefaultPersonModelContextAssembler(
            PersonMemoryGateway memoryGateway,
            RecentConversationGateway conversationGateway
    ) {
        this(
                memoryGateway,
                conversationGateway,
                DEFAULT_RECENT_EVENT_WINDOW,
                DEFAULT_MAX_MEMORY_CHARACTERS,
                DEFAULT_MAX_CONVERSATION_CHARACTERS,
                DEFAULT_MAX_RELEVANCE_QUERY_CHARACTERS
        );
    }

    public DefaultPersonModelContextAssembler(
            PersonMemoryGateway memoryGateway,
            RecentConversationGateway conversationGateway,
            Duration recentEventWindow
    ) {
        this(
                memoryGateway,
                conversationGateway,
                recentEventWindow,
                DEFAULT_MAX_MEMORY_CHARACTERS,
                DEFAULT_MAX_CONVERSATION_CHARACTERS,
                DEFAULT_MAX_RELEVANCE_QUERY_CHARACTERS
        );
    }

    public DefaultPersonModelContextAssembler(
            PersonMemoryGateway memoryGateway,
            RecentConversationGateway conversationGateway,
            Duration recentEventWindow,
            int maxMemoryCharacters,
            int maxConversationCharacters,
            int maxRelevanceQueryCharacters
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
        this.maxMemoryCharacters = requirePositive(
                maxMemoryCharacters,
                "maxMemoryCharacters"
        );
        this.maxConversationCharacters = requirePositive(
                maxConversationCharacters,
                "maxConversationCharacters"
        );
        this.maxRelevanceQueryCharacters = requirePositive(
                maxRelevanceQueryCharacters,
                "maxRelevanceQueryCharacters"
        );
    }

    public static DefaultPersonModelContextAssembler withoutExternalSources() {
        return new DefaultPersonModelContextAssembler(
                query -> CompletableFuture.completedFuture(PersonMemoryContext.disabled()),
                query -> CompletableFuture.completedFuture(List.of())
        );
    }

    @Override
    public CompletionStage<PersonModelContextSnapshot> assemble(
            Person person,
            PersonStateSnapshot currentState,
            StateEvolutionContext currentEvolution,
            PersonModelContextAssemblyRequest request,
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
        PersonModelContextAssemblyRequest options = Objects.requireNonNull(
                request,
                "request cannot be null"
        );
        Instant now = Objects.requireNonNull(
                evaluationTime,
                "evaluationTime cannot be null"
        );

        Set<String> explicitExclusions = options.excludedEventIds().stream()
                .map(EventId::toString)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<PersonEventSnapshot> activeEvents = activeEvents(
                source,
                now,
                explicitExclusions
        );
        Set<String> recentExclusions = new HashSet<>(explicitExclusions);
        activeEvents.forEach(event -> recentExclusions.add(event.eventId()));
        List<PersonEventSnapshot> recentEvents = recentEvents(
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
        String relevanceQuery = truncate(
                relevanceQuery(
                        options.relevanceSeed(),
                        options.includeEventContextInRelevanceQuery(),
                        activeEvents,
                        recentEvents
                ),
                maxRelevanceQueryCharacters
        );

        CompletionStage<PersonMemoryContext> memoryStage = Objects.requireNonNull(
                memoryGateway.retrieve(new PersonMemoryQuery(
                        source.getId(),
                        relevanceQuery,
                        EnumSet.allOf(MemorySection.class),
                        options.maxMemoryItems()
                )),
                "memoryGateway stage cannot be null"
        );
        CompletionStage<List<ConversationTurnSnapshot>> conversationStage =
                Objects.requireNonNull(
                        conversationGateway.retrieve(new RecentConversationQuery(
                                source.getId(),
                                relevanceQuery,
                                options.maxConversationTurns()
                        )),
                        "conversationGateway stage cannot be null"
                );

        return memoryStage.thenCombine(conversationStage, (memory, conversation) ->
                new PersonModelContextSnapshot(
                        source.getId(),
                        PersonIdentitySnapshot.from(source.getIdentity(), now),
                        PersonalitySnapshot.from(source.getPersonality()),
                        state,
                        activeEffects,
                        activeEvents,
                        recentEvents,
                        boundedMemory(Objects.requireNonNull(
                                memory,
                                "memory result cannot be null"
                        )),
                        boundedConversation(Objects.requireNonNull(
                                conversation,
                                "conversation result cannot be null"
                        )),
                        TemporalContextSnapshot.from(source.getIdentity(), now)
                )
        );
    }

    private PersonMemoryContext boundedMemory(PersonMemoryContext memory) {
        if (memory.items().isEmpty()) {
            return memory;
        }
        List<MemoryItem> selected = new ArrayList<>();
        int remaining = maxMemoryCharacters;
        for (MemoryItem item : memory.items()) {
            if (remaining <= 0) {
                break;
            }
            String content = truncate(item.content(), remaining);
            selected.add(new MemoryItem(
                    item.id(),
                    item.section(),
                    content,
                    item.relevance(),
                    item.createdAt(),
                    item.updatedAt()
            ));
            remaining -= content.length();
        }
        return new PersonMemoryContext(memory.availability(), selected);
    }

    private List<ConversationTurnSnapshot> boundedConversation(
            List<ConversationTurnSnapshot> conversation
    ) {
        if (conversation.isEmpty()) {
            return List.of();
        }
        List<ConversationTurnSnapshot> selected = new ArrayList<>();
        int remaining = maxConversationCharacters;
        for (int index = conversation.size() - 1; index >= 0 && remaining > 0; index--) {
            ConversationTurnSnapshot turn = Objects.requireNonNull(
                    conversation.get(index),
                    "conversation cannot contain null"
            );
            String text = truncate(turn.text(), remaining);
            selected.add(new ConversationTurnSnapshot(
                    turn.role(),
                    text,
                    turn.occurredAt()
            ));
            remaining -= text.length();
        }
        java.util.Collections.reverse(selected);
        return List.copyOf(selected);
    }

    private static String truncate(String value, int maxCharacters) {
        String normalized = Objects.requireNonNull(value, "value cannot be null");
        if (normalized.length() <= maxCharacters) {
            return normalized;
        }
        if (maxCharacters == 1) {
            return "…";
        }
        return normalized.substring(0, maxCharacters - 1).stripTrailing() + "…";
    }

    private static int requirePositive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static List<PersonEventSnapshot> activeEvents(
            Person person,
            Instant now,
            Set<String> excludedEventIds
    ) {
        List<PersonEventSnapshot> result = new ArrayList<>();
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.PERSON,
                person.getCurrentPersonEvents(now),
                excludedEventIds,
                now
        );
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.USER,
                person.getCurrentUserEvents(now),
                excludedEventIds,
                now
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
                excludedEventIds,
                now
        );
        addSnapshots(
                result,
                PersonEventSnapshot.Owner.USER,
                person.getRecentUserEvents(now, recentEventWindow),
                excludedEventIds,
                now
        );
        return sorted(result);
    }

    private static void addSnapshots(
            List<PersonEventSnapshot> target,
            PersonEventSnapshot.Owner owner,
            List<PersonEvent> events,
            Set<String> excludedEventIds,
            Instant evaluationTime
    ) {
        events.stream()
                .filter(event -> !excludedEventIds.contains(event.getId().toString()))
                .map(event -> PersonEventSnapshot.from(owner, event, evaluationTime))
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
            String seed,
            boolean includeEventContext,
            List<PersonEventSnapshot> activeEvents,
            List<PersonEventSnapshot> recentEvents
    ) {
        List<String> parts = new ArrayList<>();
        if (!seed.isBlank()) {
            parts.add(seed);
        }
        if (includeEventContext) {
            activeEvents.forEach(event -> {
                parts.add(event.owner() + " " + event.activityType() + " " + event.title());
                if (!event.notes().isBlank()) {
                    parts.add(event.notes());
                }
            });
            recentEvents.stream()
                    .skip(Math.max(0, recentEvents.size() - 5L))
                    .forEach(event -> parts.add(
                            event.owner() + " " + event.activityType() + " " + event.title()
                    ));
        }
        return String.join("\n", parts).strip();
    }
}
