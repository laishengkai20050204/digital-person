package com.laishengkai.digitalperson.infrastructure.persistence.mysql;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.experience.TimeRange;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.personality.Personality;
import com.laishengkai.digitalperson.state.EffectId;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectEndPolicy;
import com.laishengkai.digitalperson.state.StateEffectType;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import com.laishengkai.digitalperson.state.StateTransition;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResidualStateEffectPersistenceTest {
    private static final Instant START = Instant.parse("2026-07-22T08:00:00Z");

    @Test
    void roundTripsEventBoundAndFixedTimeEffectsWithCauses() {
        Person person = personWithCommunicationAndMusic();
        PersonEvent communication = person.getPersonTimeline().getAll().getFirst();
        PersonEvent music = person.getPersonTimeline().getAll().get(1);
        Instant musicStart = music.getStartTime();

        RegisteredStateEffect musicEffect = new RegisteredStateEffect(
                EffectId.random(),
                music.getId(),
                StateEffectType.COGNITIVE,
                "音乐播放占用部分注意力",
                musicStart,
                StateEffectEndPolicy.EVENT_END,
                null,
                List.of(new StateTransition(StateDimension.FOCUS, -0.1))
        );
        RegisteredStateEffect relationshipEffect = new RegisteredStateEffect(
                EffectId.random(),
                communication.getId(),
                StateEffectType.EMOTIONAL,
                "重要关系谈话引发持续低落",
                communication.getEndTime().orElseThrow(),
                StateEffectEndPolicy.FIXED_TIME,
                communication.getEndTime().orElseThrow().plus(Duration.ofHours(6)),
                List.of(new StateTransition(StateDimension.VALENCE, -0.8))
        );
        person.commitStateUpdate(
                person.getState(),
                new StateEvolutionContext(
                        musicStart,
                        Map.of(
                                musicEffect.effectId(), musicEffect,
                                relationshipEffect.effectId(), relationshipEffect
                        ),
                        Set.of(music.getId())
                )
        );

        PersonAggregateJsonMapper mapper = mapper();
        String json = mapper.write(person);
        Person restored = mapper.read(json);

        assertTrue(json.contains("\"schemaVersion\":3"));
        assertTrue(json.contains("\"cause\":\"重要关系谈话引发持续低落\""));
        assertEquals(
                person.getStateEvolutionContext(),
                restored.getStateEvolutionContext()
        );
    }

    @Test
    void readsSchemaVersionOneChannelEffectAsUnifiedEventBoundEffect() throws Exception {
        Person person = personWithCommunicationAndMusic();
        PersonEvent music = person.getPersonTimeline().getAll().get(1);
        PersonAggregateJsonMapper mapper = mapper();
        JsonMapper jsonMapper = jsonMapper();
        ObjectNode document = (ObjectNode) jsonMapper.readTree(mapper.write(person));
        document.put("schemaVersion", 1);
        ObjectNode evolution = (ObjectNode) document.get("stateEvolution");
        evolution.remove("effects");
        evolution.remove("evaluatedEventIds");
        evolution.remove("residualEffects");
        ArrayNode channels = evolution.putArray("channelEffects");
        ObjectNode channel = channels.addObject();
        channel.put("channel", "AUDIO");
        channel.put("eventId", music.getId().toString());
        ArrayNode transitions = channel.putArray("transitions");
        transitions.addObject()
                .put("dimension", "FOCUS")
                .put("shape", -0.1);

        Person restored = mapper.read(jsonMapper.writeValueAsString(document));

        assertEquals(1, restored.getStateEvolutionContext().effects().size());
        RegisteredStateEffect effect = restored.getStateEvolutionContext()
                .effects()
                .values()
                .iterator()
                .next();
        assertEquals(music.getId(), effect.sourceEventId());
        assertEquals(StateEffectType.GENERAL, effect.type());
        assertEquals(StateEffectEndPolicy.EVENT_END, effect.endPolicy());
        assertTrue(effect.cause().contains("listen to music"));
        assertTrue(restored.getStateEvolutionContext()
                .evaluatedEventIds()
                .contains(music.getId()));
    }

    @Test
    void readsSchemaVersionTwoResidualAsUnifiedFixedTimeEffect() throws Exception {
        Person person = personWithCommunicationAndMusic();
        PersonEvent communication = person.getPersonTimeline().getAll().getFirst();
        Instant end = communication.getEndTime().orElseThrow();
        PersonAggregateJsonMapper mapper = mapper();
        JsonMapper jsonMapper = jsonMapper();
        ObjectNode document = (ObjectNode) jsonMapper.readTree(mapper.write(person));
        document.put("schemaVersion", 2);
        ObjectNode evolution = (ObjectNode) document.get("stateEvolution");
        evolution.remove("effects");
        evolution.remove("evaluatedEventIds");
        evolution.putArray("channelEffects");
        ArrayNode residuals = evolution.putArray("residualEffects");
        ObjectNode residual = residuals.addObject();
        residual.put("sourceEventId", communication.getId().toString());
        residual.put("startsAt", end.toString());
        residual.put("endsAt", end.plus(Duration.ofHours(6)).toString());
        residual.putArray("transitions")
                .addObject()
                .put("dimension", "VALENCE")
                .put("shape", -0.8);

        Person restored = mapper.read(jsonMapper.writeValueAsString(document));

        assertEquals(1, restored.getStateEvolutionContext().effects().size());
        RegisteredStateEffect effect = restored.getStateEvolutionContext()
                .effects()
                .values()
                .iterator()
                .next();
        assertEquals(communication.getId(), effect.sourceEventId());
        assertEquals(StateEffectType.GENERAL, effect.type());
        assertEquals(StateEffectEndPolicy.FIXED_TIME, effect.endPolicy());
        assertEquals(end.plus(Duration.ofHours(6)), effect.fixedEndsAt());
        assertTrue(effect.cause().contains("important conversation"));
    }

    private static Person personWithCommunicationAndMusic() {
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
        return person;
    }

    private static PersonAggregateJsonMapper mapper() {
        return new PersonAggregateJsonMapper(jsonMapper());
    }

    private static JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .build();
    }
}
