package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonEventCommandResult;
import com.laishengkai.digitalperson.application.PersonEventCommandService;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.state.PersonState;
import com.laishengkai.digitalperson.state.StateEvolutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PersonEventControllerTest {
    private static final Instant NOW = Instant.parse("2026-07-22T13:00:00Z");
    private static final PersonId PERSON_ID = PersonId.parse(
            "4bfea51f-8c59-47da-846b-7c84bec71ff7"
    );

    private PersonEventCommandService commandService;
    private PersonEventController controller;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        commandService = mock(PersonEventCommandService.class);
        controller = new PersonEventController(
                commandService,
                new PersonApiProperties(true, "test-token"),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new PersonApiExceptionHandler())
                .build();
    }

    @Test
    void startsRealtimeEventAtServerCommandTime() {
        when(commandService.start(eq(PERSON_ID), any(PersonEvent.class), eq(NOW)))
                .thenAnswer(invocation -> {
                    PersonEvent event = invocation.getArgument(1);
                    return CompletableFuture.completedFuture(result(event));
                });

        ResponseEntity<?> response = controller.startRealtime(
                PERSON_ID.toString(),
                "test-token",
                new PersonEventController.RealtimeEventRequest(
                        "listen_music",
                        "听音乐",
                        "宿舍",
                        List.of("室友"),
                        ""
                )
        ).toCompletableFuture().join();

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        PersonEventController.EventCommandResponse body =
                (PersonEventController.EventCommandResponse) response.getBody();
        assertEquals("LISTEN_MUSIC", body.activityType());
        assertEquals("AUDIO", body.channel());
        assertEquals(NOW, body.startTime());
        assertEquals(null, body.endTime());

        ArgumentCaptor<PersonEvent> eventCaptor = ArgumentCaptor.forClass(PersonEvent.class);
        verify(commandService).start(eq(PERSON_ID), eventCaptor.capture(), eq(NOW));
        assertEquals(NOW, eventCaptor.getValue().getStartTime());
        assertTrue(eventCaptor.getValue().isOpen());
    }

    @Test
    void rejectsMissingTokenWithoutExecutingCommand() throws Exception {
        mockMvc.perform(post(
                        "/api/persons/{personId}/events/realtime",
                        PERSON_ID
                )
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "activityType": "STUDY",
                                  "title": "学习",
                                  "location": "",
                                  "participants": [],
                                  "notes": ""
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value("UNAUTHORIZED"));

        verify(commandService, never()).start(any(), any(), any());
    }

    @Test
    void recordsHistoricalEventWithoutChangingItsTimes() {
        Instant start = NOW.minusSeconds(3600);
        Instant end = NOW.minusSeconds(1800);
        when(commandService.recordHistorical(eq(PERSON_ID), any(PersonEvent.class), eq(NOW)))
                .thenAnswer(invocation -> {
                    PersonEvent event = invocation.getArgument(1);
                    return CompletableFuture.completedFuture(result(event));
                });

        ResponseEntity<?> response = controller.recordHistorical(
                PERSON_ID.toString(),
                "test-token",
                new PersonEventController.HistoricalEventRequest(
                        "study",
                        "复习",
                        "图书馆",
                        start,
                        end,
                        List.of(),
                        ""
                )
        ).toCompletableFuture().join();

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        ArgumentCaptor<PersonEvent> eventCaptor = ArgumentCaptor.forClass(PersonEvent.class);
        verify(commandService).recordHistorical(
                eq(PERSON_ID),
                eventCaptor.capture(),
                eq(NOW)
        );
        assertEquals(start, eventCaptor.getValue().getStartTime());
        assertEquals(end, eventCaptor.getValue().getEndTime().orElseThrow());
    }

    @Test
    void reservesReplacedReasonForStartSemantics() {
        assertThrows(
                IllegalArgumentException.class,
                () -> controller.finish(
                        PERSON_ID.toString(),
                        EventId.random().toString(),
                        "test-token",
                        new PersonEventController.FinishEventRequest("replaced")
                )
        );
        verify(commandService, never()).finish(any(), any(), any(), any());
    }

    private static PersonEventCommandResult result(PersonEvent event) {
        PersonEvent committed = event.copy();
        if (!committed.isOpen() && !committed.isFinished()) {
            PersonEvent finished = new PersonEvent(
                    committed.getId(),
                    committed.getActivityType(),
                    committed.getTitle(),
                    committed.getLocation(),
                    committed.getTimeRange(),
                    committed.getParticipants(),
                    committed.getNotes()
            );
            return new PersonEventCommandResult(
                    PERSON_ID,
                    finished,
                    PersonState.baseline().snapshot(),
                    StateEvolutionContext.initial()
            );
        }
        return new PersonEventCommandResult(
                PERSON_ID,
                committed,
                PersonState.baseline().snapshot(),
                StateEvolutionContext.initial()
        );
    }
}
