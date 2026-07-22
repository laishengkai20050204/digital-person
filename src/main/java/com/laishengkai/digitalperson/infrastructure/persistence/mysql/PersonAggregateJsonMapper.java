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
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.AffectState;
import com.laishengkai.digitalperson.state.ChannelStateEffect;
import com.laishengkai.digitalperson.state.CognitiveState;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.PhysicalState;
import com.laishengkai.digitalperson.state.SocialState;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransition;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Converts between the domain aggregate and the adapter-owned JSON schema. */
final class PersonAggregateJsonMapper {
    static final int CURRENT_SCHEMA_VERSION = 1;

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
            return fromDocument(objectMapper.readValue(
                    source,
                    PersonAggregateDocument.class
            ));
        } catch (JsonProcessingException | IllegalArgumentException | IllegalStateException error) {
            throw new PersonPersistenceException(
                    "failed to decode person aggregate document",
                    error
            );
        }
    }

    private static PersonAggregateDocument toDocument(Person person) {
        Personality personality = person.getPersonality();
        PersonStateSnapshot state = person.getStateSnapshot();
        StateEvolutionContext evolution = person.getStateEvolutionContext();

        List<PersonAggregateDocument.ChannelEffectDocument> effects = evolution
                .channelEffects()
                .entrySet()
                .stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> toDocument(entry.getValue()))
                .toList();

        return new PersonAggregateDocument(
                CURRENT_SCHEMA_VERSION,
                person.getId().toString(),
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
                        effects
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

    private static PersonAggregateDocument.ChannelEffectDocument toDocument(
            ChannelStateEffect effect
    ) {
        return new PersonAggregateDocument.ChannelEffectDocument(
                effect.channel().name(),
                effect.eventId().toString(),
                effect.transitions().stream()
                        .map(transition -> new PersonAggregateDocument.TransitionDocument(
                                transition.dimension().name(),
                                transition.shape()
                        ))
                        .toList()
        );
    }

    private static Person fromDocument(PersonAggregateDocument document) {
        PersonAggregateDocument source = Objects.requireNonNull(
                document,
                "document cannot be null"
        );
        if (source.schemaVersion() != CURRENT_SCHEMA_VERSION) {
            throw new PersonPersistenceException(
                    "unsupported person aggregate schema version: "
                            + source.schemaVersion()
            );
        }

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
        StateEvolutionContext evolution = restoreEvolution(
                source.stateEvolution(),
                personTimeline
        );

        return new Person(
                PersonId.parse(source.personId()),
                personality,
                state,
                personTimeline,
                userTimeline,
                evolution
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

    private static StateEvolutionContext restoreEvolution(
            PersonAggregateDocument.StateEvolutionDocument document,
            EventTimeline personTimeline
    ) {
        Map<ActivityChannel, ChannelStateEffect> effects = new EnumMap<>(
                ActivityChannel.class
        );
        Set<StateDimension> dimensions = new HashSet<>();

        for (PersonAggregateDocument.ChannelEffectDocument storedEffect
                : document.channelEffects()) {
            ActivityChannel channel = ActivityChannel.valueOf(storedEffect.channel());
            EventId eventId = EventId.parse(storedEffect.eventId());
            PersonEvent event = personTimeline.getById(eventId).orElseThrow(
                    () -> new IllegalStateException(
                            "state effect references an unknown person event"
                    )
            );
            if (event.getChannel() != channel) {
                throw new IllegalStateException(
                        "state effect channel does not match referenced event"
                );
            }
            if (!event.isOpen()) {
                throw new IllegalStateException(
                        "state effect cannot reference a finished event"
                );
            }

            dimensions.clear();
            List<StateTransition> transitions = storedEffect.transitions().stream()
                    .map(storedTransition -> new StateTransition(
                            StateDimension.valueOf(storedTransition.dimension()),
                            storedTransition.shape()
                    ))
                    .peek(transition -> {
                        if (!dimensions.add(transition.dimension())) {
                            throw new IllegalStateException(
                                    "duplicate transition dimension in one channel effect"
                            );
                        }
                    })
                    .toList();
            ChannelStateEffect effect = new ChannelStateEffect(
                    channel,
                    eventId,
                    transitions
            );
            if (effects.put(channel, effect) != null) {
                throw new IllegalStateException(
                        "duplicate state effect channel in stored aggregate"
                );
            }
        }
        return new StateEvolutionContext(document.lastUpdatedAt(), effects);
    }
}
