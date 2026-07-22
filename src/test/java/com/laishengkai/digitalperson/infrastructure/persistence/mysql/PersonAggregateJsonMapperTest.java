package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import com.laishengkai.digitalperson.state.PhysicalState;
import com.laishengkai.digitalperson.state.SocialState;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonAggregateJsonMapperTest {
    private static final Instant NOW = Instant.parse("2026-07-22T12:00:00Z");

    @Test
    void roundTripsCompleteAggregateWithoutLosingDomainState() {
        Person source = completePerson();
        PersonAggregateJsonMapper mapper = new PersonAggregateJsonMapper(objectMapper());

        String json = mapper.write(source);
        Person restored = mapper.read(json);

        assertTrue(json.contains("\"schemaVersion\":1"));
        assertEquals(source.getId(), restored.getId());
        assertEquals(source.getPersonality(), restored.getPersonality());
        assertEquals(source.getStateSnapshot(), restored.getStateSnapshot());
        assertEquals(
                source.getPersonTimeline().getAll().stream()
                        .map(PersonAggregateJsonMapperTest::eventView)
                        .toList(),
                restored.getPersonTimeline().getAll().stream()
                        .map(PersonAggregateJsonMapperTest::eventView)
                        .toList()
        );
        assertEquals(
                source.getUserTimeline().getAll().stream()
                        .map(PersonAggregateJsonMapperTest::eventView)
                        .toList(),
                restored.getUserTimeline().getAll().stream()
                        .map(PersonAggregateJsonMapperTest::eventView)
                        .toList()
        );
        assertEquals(
                source.getStateEvolutionContext(),
                restored.getStateEvolutionContext()
        );
    }

    @Test
    void rejectsUnknownDocumentSchemaVersion() {
        PersonAggregateJsonMapper mapper = new PersonAggregateJsonMapper(objectMapper());
        String json = mapper.write(completePerson())
                .replace("\"schemaVersion\":1", "\"schemaVersion\":99");

        assertThrows(PersonPersistenceException.class, () -> mapper.read(json));
    }

    private static Person completePerson() {
        Personality personality = new Personality(0.7, 0.8, 0.4, 0.6, 0.9, 0.75);
        PersonState state = new PersonState(
                new AffectState(-0.2, 0.65, 0.35),
                new CognitiveState(0.8, 0.3, 0.7),
                new PhysicalState(0.2, 0.1, 0.55),
                new SocialState(0.4, 0.6)
        );

        EventTimeline personTimeline = new EventTimeline();
        PersonEvent historical = new PersonEvent(
                EventId.random(),
                ActivityType.EAT,
                "午饭",
                "食堂",
                TimeRange.closed(NOW.minusSeconds(7200), NOW.minusSeconds(6600)),
                List.of("朋友甲"),
                "正常吃完"
        );
        personTimeline.record(historical, EventEndReason.COMPLETED, NOW.minusSeconds(6600));

        PersonEvent listening = new PersonEvent(
                EventId.random(),
                ActivityType.LISTEN_MUSIC,
                "听音乐",
                "宿舍",
                TimeRange.openEnded(NOW.minusSeconds(300)),
                List.of(),
                "放松"
        );
        personTimeline.start(listening, NOW.minusSeconds(300));

        EventTimeline userTimeline = new EventTimeline();
        PersonEvent userStudy = new PersonEvent(
                EventId.random(),
                ActivityType.STUDY,
                "复习",
                "图书馆",
                TimeRange.closed(NOW.minusSeconds(3600), NOW.minusSeconds(1800)),
                List.of(),
                "线性代数"
        );
        userTimeline.record(userStudy, EventEndReason.INTERRUPTED, NOW.minusSeconds(1800));

        StateEvolutionContext evolution = new StateEvolutionContext(
                NOW.minusSeconds(300),
                Map.of(
                        ActivityChannel.AUDIO,
                        new ChannelStateEffect(
                                ActivityChannel.AUDIO,
                                listening.getId(),
                                List.of(
                                        new StateTransition(StateDimension.TENSION, -0.25),
                                        new StateTransition(StateDimension.VALENCE, 0.2)
                                )
                        )
                )
        );

        return new Person(
                PersonId.random(),
                personality,
                state,
                personTimeline,
                userTimeline,
                evolution
        );
    }

    private static EventView eventView(PersonEvent event) {
        return new EventView(
                event.getId(),
                event.getActivityType(),
                event.getTitle(),
                event.getLocation(),
                event.getStartTime(),
                event.getEndTime().orElse(null),
                event.getParticipants(),
                event.getNotes(),
                event.getEndReason().orElse(null)
        );
    }

    private static ObjectMapper objectMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }

    private record EventView(
            EventId eventId,
            ActivityType activityType,
            String title,
            String location,
            Instant startTime,
            Instant endTime,
            List<String> participants,
            String notes,
            EventEndReason endReason
    ) {
    }
}
