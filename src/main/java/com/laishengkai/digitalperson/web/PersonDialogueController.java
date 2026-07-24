package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonDialogueExchange;
import com.laishengkai.digitalperson.application.PersonDialogueService;
import com.laishengkai.digitalperson.person.PersonId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Token-protected formal HTTP boundary for direct user-to-person dialogue. */
@RestController
@RequestMapping("/api/persons/{personId}/dialogues")
@ConditionalOnBean(PersonDialogueService.class)
@ConditionalOnProperty(
        prefix = "digital-person.person-api",
        name = "enabled",
        havingValue = "true"
)
public final class PersonDialogueController {
    private final PersonDialogueService dialogueService;
    private final InternalTokenGuard tokenGuard;

    public PersonDialogueController(
            PersonDialogueService dialogueService,
            PersonApiProperties properties
    ) {
        this.dialogueService = Objects.requireNonNull(
                dialogueService,
                "dialogueService cannot be null"
        );
        this.tokenGuard = new InternalTokenGuard(Objects.requireNonNull(
                properties,
                "properties cannot be null"
        ).requiredToken());
    }

    @PostMapping
    public CompletionStage<ResponseEntity<DialogueResponse>> dialogue(
            @PathVariable String personId,
            @RequestHeader(
                    name = PersonController.INTERNAL_TOKEN_HEADER,
                    required = false
            ) String suppliedToken,
            @RequestBody DialogueRequest request
    ) {
        tokenGuard.requireAuthorized(suppliedToken);
        PersonId parsedPersonId = PersonId.parse(personId);
        String message = Objects.requireNonNull(
                request,
                "request cannot be null"
        ).message();
        return dialogueService.dialogue(parsedPersonId, message)
                .thenApply(exchange -> ResponseEntity.ok(DialogueResponse.from(exchange)));
    }

    public record DialogueRequest(String message) {
    }

    public record DialogueResponse(
            String personId,
            List<String> replies,
            Instant occurredAt,
            String memoryStatus,
            int memoryMutationCount
    ) {
        static DialogueResponse from(PersonDialogueExchange exchange) {
            return new DialogueResponse(
                    exchange.personId().toString(),
                    exchange.result().replies(),
                    exchange.occurredAt(),
                    exchange.memoryStatus().name(),
                    exchange.memoryMutationCount()
            );
        }
    }
}
