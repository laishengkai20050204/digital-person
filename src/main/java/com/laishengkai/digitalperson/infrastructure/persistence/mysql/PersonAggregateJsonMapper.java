package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.EventTimeline;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonIdentity;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.AffectState;
import com.laishengkai.digitalperson.state.CognitiveState;
import com.laishengkai.digitalperson.state.EffectId;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.PhysicalState;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import com.laishengkai.digitalperson.state.SocialState;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectEndPolicy;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransition;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Converts between the domain aggregate and the adapter-owned JSON schema. */
final class PersonAggregateJsonMapper {
    static final int CURRENT_SCHEMA_VERSION = 4;
    private static final int OLDEST_SUPPORTED_SCHEMA_VERSION = 1;

    private final ObjectMapper objectMapper;

    PersonAggregateJsonMapper(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(
                objectMapper,
                "objectMapper cannot be null"
        ).copy();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    String write(Person person) {
        Person source = Objects.requireNonNull(person, "person cannot be null");
        try {
            return objectMapper.writeValueAsString(toDocument(source));
        } catch (JsonProcessingException error) {
            throw new PersonPersistenceException(
                    "failed to encode person aggregate: personId=" + source.getId(),
                    error
            );
        }
    }

    Person read(String json) {
        String source = Objects.requireNonNull(json, "json cannot be null");
        try {
            PersonAggregateDocument document = objectMapper.readValue(
                    source,
                    PersonAggregateDocument.class
            );
            return fromDocument(document);
        } catch (JsonProcessingException | IllegalArgumentException | IllegalStateException error) {
            throw new PersonPersistenceException(
                    "failed to decode person aggregate document",
                    error
            );
        }
    }

    private static PersonAggregateDocument toDocument(Person person) {
        PersonIdentity identity = person.getIdentity();
        Personality personality = person.getPersonality();
        PersonStateSnapshot state = person.getStateSnapshot();
        StateEvolutionContext evolution = person.getStateEvolutionContext();

        List<PersonAggregateDocument.StateEffectDocument> effects = evolution.effects()
                .values()
                .stream()
                .sorted(Comparator.comparing(RegisteredStateEffect::effectId))
                .map(PersonAggregateJsonMapper::toDocument)
                .toList();
        List<String> evaluatedEventIds = evolution.evaluatedEventIds().stream()
                .sorted()
                .map(EventId::toString)
                .toList();

        return new PersonAggregateDocument(
                CURRENT_SCHEMA_VERSION,
                person.getId().toString(),
                new PersonAggregateDocument.IdentityDocument(
                        identity.displayName(),
                        identity.birthDate(),
                        identity.genderIdentity(),
                        identity.residence(),
                        identity.timeZone().getId(),
                        identity.locale().toLanguageTag(),
                        identity.roles(),
                        identity.background()
                ),
                new PersonAggregateDocument.PersonalityDocument(
                        personality.getHonestyHumility(),
                        personality.getEmotionality(),
                        personality.getExtraversion(),
                        personality.getAgreeableness(),
                        personality.getConscientiousness(),
                        personality.getOpenness()
                ),
                new PersonAggregateDocument.StateDocument(
                        state.valence(),
                        state.energy(),
                        state.tension(),
                        state.focus(),
                        state.mentalLoad(),
                        state.motivation(),
                        state.fatigue(),
                        state.sleepiness(),
                        state.hunger(),
                        state.loneliness(),
                        state.socialNeed()
                ),
                person.getPersonTimeline().getAll().stream()
                        .map(PersonAggregateJsonMapper::toDocument)
                        .toList(),
                person.getUserTimeline().getAll().stream()
                        .map(PersonAggregateJsonMapper::toDocument)
                        .toList(),
                new PersonAggregateDocument.StateEvolutionDocument(
                        evolution.lastUpdatedAt(),
                        effects,
                        evaluatedEventIds,
                        List.of(),
                        List.of()
                )
        );
    }

    private static PersonAggregateDocument.EventDocument toDocument(PersonEvent event) {
        return new PersonAggregateDocument.EventDocument(
                event.getId().toString(),
                event.getActivityType().name(),
                event.getTitle(),
                event.getLocation(),
                event.getStartTime(),
                event.getEndTime().orElse(null),
                event.getParticipants(),
                event.getNotes(),
                event.getEndReason().map(Enum::name).orElse(null)
        );
    }

    private static PersonAggregateDocument.StateEffectDocument toDocument(
            RegisteredStateEffect effect
    ) {
        return new PersonAggregateDocument.StateEffectDocument(
                effect.effectId().toString(),
                effect.sourceEventId() == null ? null : effect.sourceEventId().toString(),
                effect.type().name(),
                effect.cause(),
                effect.startsAt(),
                effect.endPolicy().name(),
                effect.fixedEndsAt(),
                effect.transitions().stream()
                        .map(PersonAggregateJsonMapper::toDocument)
                        .toList()
        );
    }

    private static PersonAggregateDocument.TransitionDocument toDocument(
            StateTransition transition
    ) {
        return new PersonAggregateDocument.TransitionDocument(
                transition.dimension().name(),
                transition.shape()
        );
    }

    private static Person fromDocument(PersonAggregateDocument document) {
        PersonAggregateDocument source = Objects.requireNonNull(
                document,
                "document cannot be null"
        );
        if (source.schemaVersion() < OLDEST_SUPPORTED_SCHEMA_VERSION
                || source.schemaVersion() > CURRENT_SCHEMA_VERSION) {
            throw new PersonPersistenceException(
                    "unsupported person aggregate schema version: "
                            + source.schemaVersion()
            );
        }

        PersonIdentity identity = restoreIdentity(source);
        Personality personality = new Personality(
                source.personality().honestyHumility(),
                source.personality().emotionality(),
                source.personality().extraversion(),
                source.personality().agreeableness(),
                source.personality().conscientiousness(),
                source.personality().openness()
        );
        PersonAggregateDocument.StateDocument storedState = source.state();
        PersonState state = new PersonState(
                new AffectState(
                        storedState.valence(),
                        storedState.energy(),
                        storedState.tension()
                ),
                new CognitiveState(
                        storedState.focus(),
                        storedState.mentalLoad(),
                        storedState.motivation()
                ),
                new PhysicalState(
                        storedState.fatigue(),
                        storedState.sleepiness(),
                        storedState.hunger()
                ),
                new SocialState(
                        storedState.loneliness(),
                        storedState.socialNeed()
                )
        );

        EventTimeline personTimeline = restoreTimeline(source.personEvents());
        EventTimeline userTimeline = restoreTimeline(source.userEvents());
        StateEvolutionContext evolution = source.schemaVersion() >= 3
                ? restoreUnifiedEvolution(source.stateEvolution(), personTimeline)
                : migrateLegacyEvolution(source.stateEvolution(), personTimeline);

        return new Person(
                PersonId.parse(source.personId()),
                identity,
                personality,
                state,
                personTimeline,
                userTimeline,
                evolution
        );
    }

    private static PersonIdentity restoreIdentity(PersonAggregateDocument document) {
        if (document.schemaVersion() < 4) {
            return PersonIdentity.unspecified();
        }
        PersonAggregateDocument.IdentityDocument stored = document.identity();
        if (stored == null) {
            throw new IllegalStateException("schema v4 identity cannot be null");
        }
        return new PersonIdentity(
                stored.displayName(),
                stored.birthDate(),
                stored.genderIdentity(),
                stored.residence(),
                ZoneId.of(stored.timeZone()),
                Locale.forLanguageTag(stored.locale()),
                stored.roles(),
                stored.background()
        );
    }

    private static EventTimeline restoreTimeline(
            List<PersonAggregateDocument.EventDocument> documents
    ) {
        List<PersonAggregateDocument.EventDocument> ordered = new ArrayList<>(documents);
        ordered.sort(Comparator.comparing(
                PersonAggregateDocument.EventDocument::startTime
        ));
        validateOpenEventOrdering(ordered);

        EventTimeline timeline = new EventTimeline();
        for (PersonAggregateDocument.EventDocument document : ordered) {
            TimeRange range = document.endTime() == null
                    ? TimeRange.openEnded(document.startTime())
                    : TimeRange.closed(document.startTime(), document.endTime());
            PersonEvent event = new PersonEvent(
                    EventId.parse(document.eventId()),
                    ActivityType.valueOf(document.activityType()),
                    document.title(),
                    document.location(),
                    range,
                    document.participants(),
                    document.notes()
            );
            if (document.endReason() == null) {
                timeline.start(event, document.startTime());
            } else {
                timeline.record(
                        event,
                        EventEndReason.valueOf(document.endReason()),
                        document.endTime()
                );
            }
        }
        return timeline;
    }

    private static void validateOpenEventOrdering(
            List<PersonAggregateDocument.EventDocument> ordered
    ) {
        Map<ActivityChannel, Instant> latestStartByChannel = new EnumMap<>(
                ActivityChannel.class
        );
        for (PersonAggregateDocument.EventDocument document : ordered) {
            ActivityChannel channel = ActivityType.valueOf(
                    document.activityType()
            ).getChannel();
            latestStartByChannel.put(channel, document.startTime());
        }
        for (PersonAggregateDocument.EventDocument document : ordered) {
            if (document.endTime() != null) {
                continue;
            }
            ActivityChannel channel = ActivityType.valueOf(
                    document.activityType()
            ).getChannel();
            if (!document.startTime().equals(latestStartByChannel.get(channel))) {
                throw new IllegalStateException(
                        "an open event must be the latest event in its channel"
                );
            }
        }
    }

    private static StateEvolutionContext restoreUnifiedEvolution(
            PersonAggregateDocument.StateEvolutionDocument document,
            EventTimeline personTimeline
    ) {
        Map<EffectId, RegisteredStateEffect> effects = new HashMap<>();
        for (PersonAggregateDocument.StateEffectDocument storedEffect : document.effects()) {
            EffectId effectId = EffectId.parse(storedEffect.effectId());
            EventId sourceEventId = storedEffect.sourceEventId() == null
                    ? null
                    : EventId.parse(storedEffect.sourceEventId());
            if (sourceEventId != null && personTimeline.getById(sourceEventId).isEmpty()) {
                throw new IllegalStateException(
                        "state effect references an unknown person event"
                );
            }
            RegisteredStateEffect effect = new RegisteredStateEffect(
                    effectId,
                    sourceEventId,
                    StateEffectType.valueOf(storedEffect.type()),
                    storedEffect.cause(),
                    storedEffect.startsAt(),
                    StateEffectEndPolicy.valueOf(storedEffect.endPolicy()),
                    storedEffect.fixedEndsAt(),
                    restoreTransitions(storedEffect.transitions())
            );
            if (effects.put(effectId, effect) != null) {
                throw new IllegalStateException("duplicate persisted effect id");
            }
        }

        Set<EventId> evaluatedEventIds = new HashSet<>();
        for (String storedEventId : document.evaluatedEventIds()) {
            EventId eventId = EventId.parse(storedEventId);
            PersonEvent event = personTimeline.getById(eventId).orElseThrow(
                    () -> new IllegalStateException(
                            "evaluated event marker references an unknown person event"
                    )
            );
            if (!event.isOpen()) {
                throw new IllegalStateException(
                        "only open events may retain evaluated markers"
                );
            }
            evaluatedEventIds.add(eventId);
        }
        return new StateEvolutionContext(
                document.lastUpdatedAt(),
                effects,
                evaluatedEventIds
        );
    }

    private static StateEvolutionContext migrateLegacyEvolution(
            PersonAggregateDocument.StateEvolutionDocument document,
            EventTimeline personTimeline
    ) {
        Map<EffectId, RegisteredStateEffect> effects = new HashMap<>();
        Set<EventId> evaluatedEventIds = new HashSet<>();

        for (PersonAggregateDocument.ChannelEffectDocument legacy : document.channelEffects()) {
            EventId eventId = EventId.parse(legacy.eventId());
            PersonEvent event = personTimeline.getById(eventId).orElseThrow(
                    () -> new IllegalStateException(
                            "legacy state effect references an unknown person event"
                    )
            );
            if (event.isOpen()) {
                evaluatedEventIds.add(eventId);
            }
            List<StateTransition> activeTransitions = restoreTransitions(legacy.transitions());
            if (!activeTransitions.isEmpty()) {
                putLegacyEffect(effects, new RegisteredStateEffect(
                        legacyEffectId(eventId, "active"),
                        eventId,
                        StateEffectType.GENERAL,
                        "Legacy event-bound effect: " + event.getTitle(),
                        event.getStartTime(),
                        StateEffectEndPolicy.EVENT_END,
                        null,
                        activeTransitions
                ));
            }
            if (legacy.aftermath() != null) {
                Instant start = document.lastUpdatedAt() == null
                        ? event.getStartTime()
                        : document.lastUpdatedAt();
                putLegacyEffect(effects, new RegisteredStateEffect(
                        legacyEffectId(eventId, "pending-aftermath"),
                        eventId,
                        StateEffectType.GENERAL,
                        "Legacy post-event effect: " + event.getTitle(),
                        start,
                        StateEffectEndPolicy.FIXED_TIME,
                        start.plusSeconds(legacy.aftermath().durationSeconds()),
                        restoreTransitions(legacy.aftermath().transitions())
                ));
            }
        }

        int residualIndex = 0;
        for (PersonAggregateDocument.ResidualEffectDocument legacy : document.residualEffects()) {
            EventId eventId = EventId.parse(legacy.sourceEventId());
            PersonEvent event = personTimeline.getById(eventId).orElseThrow(
                    () -> new IllegalStateException(
                            "legacy residual effect references an unknown person event"
                    )
            );
            putLegacyEffect(effects, new RegisteredStateEffect(
                    legacyEffectId(eventId, "residual-" + residualIndex++),
                    eventId,
                    StateEffectType.GENERAL,
                    "Legacy fixed-time effect: " + event.getTitle(),
                    legacy.startsAt(),
                    StateEffectEndPolicy.FIXED_TIME,
                    legacy.endsAt(),
                    restoreTransitions(legacy.transitions())
            ));
        }

        return new StateEvolutionContext(
                document.lastUpdatedAt(),
                effects,
                evaluatedEventIds
        );
    }

    private static void putLegacyEffect(
            Map<EffectId, RegisteredStateEffect> effects,
            RegisteredStateEffect effect
    ) {
        if (effects.put(effect.effectId(), effect) != null) {
            throw new IllegalStateException("duplicate migrated effect id");
        }
    }

    private static EffectId legacyEffectId(EventId eventId, String suffix) {
        UUID value = UUID.nameUUIDFromBytes(
                (eventId + ":" + suffix).getBytes(StandardCharsets.UTF_8)
        );
        return new EffectId(value);
    }

    private static List<StateTransition> restoreTransitions(
            List<PersonAggregateDocument.TransitionDocument> documents
    ) {
        Set<StateDimension> dimensions = new HashSet<>();
        return documents.stream()
                .map(stored -> new StateTransition(
                        StateDimension.valueOf(stored.dimension()),
                        boundPersistedShape(stored.shape())
                ))
                .peek(transition -> {
                    if (!dimensions.add(transition.dimension())) {
                        throw new IllegalStateException(
                                "duplicate transition dimension in one effect"
                        );
                    }
                })
                .toList();
    }

    private static double boundPersistedShape(double shape) {
        return Math.max(
                -StateTransition.MAX_ABSOLUTE_SHAPE,
                Math.min(StateTransition.MAX_ABSOLUTE_SHAPE, shape)
        );
    }
}
