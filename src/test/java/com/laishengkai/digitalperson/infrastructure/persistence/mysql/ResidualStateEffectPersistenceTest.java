package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.AftermathStateEffectPlan;
import com.laishengkai.digitalperson.state.ChannelStateEffect;
import com.laishengkai.digitalperson.state.ResidualStateEffect;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidualStateEffectPersistenceTest {
    private static final Instant START = Instant.parse("2026-07-22T08:00:00Z");

    @Test
    void roundTripsActiveAftermathPlanAndIndependentResidualEffect() {
        Person person = new Person(new Personality(0.5, 0.7, 0.4, 0.8, 0.6, 0.9));
        Instant communicationEnd = START.plus(Duration.ofMinutes(10));
        PersonEvent communication = new PersonEvent(
                EventId.random(),
                ActivityType.CHAT,
                "important conversation",
                "",
                TimeRange.closed(START, communicationEnd),
                List.of(),
                ""
        );
        person.recordPersonEvent(communication, communicationEnd);

        Instant musicStart = communicationEnd.plus(Duration.ofMinutes(5));
        PersonEvent music = new PersonEvent(
                ActivityType.LISTEN_MUSIC,
                "listen to music",
                "",
                TimeRange.openEnded(musicStart)
        );
        person.startPersonEvent(music, musicStart);

        AftermathStateEffectPlan musicAftermath = new AftermathStateEffectPlan(
                Duration.ofHours(1),
                List.of(new StateTransition(StateDimension.TENSION, -0.2))
        );
        ResidualStateEffect communicationResidual = new ResidualStateEffect(
                communication.getId(),
                communicationEnd,
                communicationEnd.plus(Duration.ofHours(6)),
                List.of(new StateTransition(StateDimension.VALENCE, -0.8))
        );
        person.commitStateUpdate(
                person.getState(),
                new StateEvolutionContext(
                        musicStart,
                        Map.of(
                                ActivityChannel.AUDIO,
                                new ChannelStateEffect(
                                        ActivityChannel.AUDIO,
                                        music.getId(),
                                        List.of(new StateTransition(
                                                StateDimension.FOCUS,
                                                -0.1
                                        )),
                                        musicAftermath
                                )
                        ),
                        Map.of(communication.getId(), communicationResidual)
                )
        );

        PersonAggregateJsonMapper mapper = mapper();
        String json = mapper.write(person);
        Person restored = mapper.read(json);

        assertTrue(json.contains("\"schemaVersion\":2"));
        ChannelStateEffect restoredMusic = restored.getStateEvolutionContext()
                .channelEffects()
                .get(ActivityChannel.AUDIO);
        assertEquals(musicAftermath, restoredMusic.aftermath());
        assertEquals(
                communicationResidual,
                restored.getStateEvolutionContext()
                        .residualEffects()
                        .get(communication.getId())
        );
    }

    @Test
    void readsExistingSchemaVersionOneAsActiveOnlyState() throws Exception {
        Person person = new Person(new Personality(0.5, 0.5, 0.5, 0.5, 0.5, 0.5));
        PersonEvent music = new PersonEvent(
                ActivityType.LISTEN_MUSIC,
                "listen to music",
                "",
                TimeRange.openEnded(START)
        );
        person.startPersonEvent(music, START);
        person.commitStateUpdate(
                person.getState(),
                new StateEvolutionContext(
                        START,
                        Map.of(
                                ActivityChannel.AUDIO,
                                new ChannelStateEffect(
                                        ActivityChannel.AUDIO,
                                        music.getId(),
                                        List.of(new StateTransition(
                                                StateDimension.FOCUS,
                                                -0.1
                                        ))
                                )
                        )
                )
        );

        PersonAggregateJsonMapper mapper = mapper();
        JsonMapper jsonMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
        ObjectNode document = (ObjectNode) jsonMapper.readTree(mapper.write(person));
        document.put("schemaVersion", 1);
        ObjectNode evolution = (ObjectNode) document.get("stateEvolution");
        evolution.remove("residualEffects");
        evolution.withArray("channelEffects").forEach(effect ->
                ((ObjectNode) effect).remove("aftermath")
        );

        Person restored = mapper.read(jsonMapper.writeValueAsString(document));

        assertTrue(restored.getStateEvolutionContext().residualEffects().isEmpty());
        assertFalse(restored.getStateEvolutionContext()
                .channelEffects()
                .get(ActivityChannel.AUDIO)
                .hasAftermath());
    }

    private static PersonAggregateJsonMapper mapper() {
        return new PersonAggregateJsonMapper(
                JsonMapper.builder()
                        .addModule(new JavaTimeModule())
                        .build()
        );
    }
}
