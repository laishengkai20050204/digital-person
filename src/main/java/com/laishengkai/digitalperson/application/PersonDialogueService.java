package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.dialogue.PersonDialogueException;
import com.laishengkai.digitalperson.dialogue.PersonDialogueModel;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Coordinates context retrieval, direct reply generation and fail-open memory recording. */
public final class PersonDialogueService {
    public static final int MAX_USER_MESSAGE_CHARACTERS = 16_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonDialogueService.class);

    private final PersonRepository personRepository;
    private final PersonModelContextAssembler contextAssembler;
    private final PersonDialogueModel dialogueModel;
    private final DialogueMemoryRecorder memoryRecorder;
    private final Clock clock;
    private final int maxMemoryItems;
    private final int maxConversationTurns;

    public PersonDialogueService(
            PersonRepository personRepository,
            PersonModelContextAssembler contextAssembler,
            PersonDialogueModel dialogueModel,
            DialogueMemoryRecorder memoryRecorder,
            Clock clock,
            int maxMemoryItems,
            int maxConversationTurns
    ) {
        this.personRepository = Objects.requireNonNull(
                personRepository,
                "personRepository cannot be null"
        );
        this.contextAssembler = Objects.requireNonNull(
                contextAssembler,
                "contextAssembler cannot be null"
        );
        this.dialogueModel = Objects.requireNonNull(
                dialogueModel,
                "dialogueModel cannot be null"
        );
        this.memoryRecorder = memoryRecorder;
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.maxMemoryItems = positive(maxMemoryItems, "maxMemoryItems");
        this.maxConversationTurns = positive(
                maxConversationTurns,
                "maxConversationTurns"
        );
    }

    public CompletionStage<PersonDialogueExchange> dialogue(
            PersonId personId,
            String userMessage
    ) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        String normalizedMessage = requireUserMessage(userMessage);
        Instant occurredAt = clock.instant();

        final VersionedPerson loaded;
        try {
            loaded = personRepository.findById(requestedPersonId)
                    .orElseThrow(() -> new PersonNotFoundException(requestedPersonId));
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }

        Person person = loaded.person().copy();
        PersonModelContextAssemblyRequest contextRequest =
                new PersonModelContextAssemblyRequest(
                        Set.of(),
                        normalizedMessage,
                        true,
                        maxMemoryItems,
                        maxConversationTurns
                );

        final CompletionStage<com.laishengkai.digitalperson.modelcontext.PersonModelContextSnapshot>
                contextStage;
        try {
            contextStage = Objects.requireNonNull(
                    contextAssembler.assemble(
                            person,
                            person.getStateSnapshot(),
                            person.getStateEvolutionContext(),
                            contextRequest,
                            occurredAt
                    ),
                    "contextAssembler stage cannot be null"
            );
        } catch (RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }

        return contextStage.thenCompose(context -> {
            if (context == null) {
                throw new CompletionException(new PersonDialogueException(
                        "person dialogue context was not assembled"
                ));
            }
            return Objects.requireNonNull(
                    dialogueModel.reply(context, normalizedMessage),
                    "dialogueModel stage cannot be null"
            );
        }).thenCompose(result -> completeExchange(
                requestedPersonId,
                normalizedMessage,
                requireResult(result),
                occurredAt
        ));
    }

    private CompletionStage<PersonDialogueExchange> completeExchange(
            PersonId personId,
            String userMessage,
            DialogueResult result,
            Instant occurredAt
    ) {
        if (memoryRecorder == null) {
            return CompletableFuture.completedFuture(new PersonDialogueExchange(
                    personId,
                    result,
                    occurredAt,
                    PersonDialogueExchange.MemoryStatus.DISABLED,
                    0
            ));
        }

        final CompletionStage<List<com.laishengkai.digitalperson.memory.MemoryMutation>> stage;
        try {
            stage = Objects.requireNonNull(
                    memoryRecorder.record(personId, userMessage, result, occurredAt),
                    "memoryRecorder stage cannot be null"
            );
        } catch (RuntimeException error) {
            logMemoryFailure(personId, error);
            return CompletableFuture.completedFuture(new PersonDialogueExchange(
                    personId,
                    result,
                    occurredAt,
                    PersonDialogueExchange.MemoryStatus.FAILED,
                    0
            ));
        }

        return stage.handle((mutations, failure) -> {
            if (failure != null) {
                logMemoryFailure(personId, unwrap(failure));
                return new PersonDialogueExchange(
                        personId,
                        result,
                        occurredAt,
                        PersonDialogueExchange.MemoryStatus.FAILED,
                        0
                );
            }
            List<?> safeMutations = Objects.requireNonNullElse(mutations, List.of());
            return new PersonDialogueExchange(
                    personId,
                    result,
                    occurredAt,
                    PersonDialogueExchange.MemoryStatus.PROCESSED,
                    safeMutations.size()
            );
        });
    }

    private static DialogueResult requireResult(DialogueResult result) {
        DialogueResult safeResult = Objects.requireNonNull(
                result,
                "dialogueModel result cannot be null"
        );
        if (safeResult.replies().isEmpty()) {
            throw new PersonDialogueException("dialogue model returned no user-facing reply");
        }
        if (safeResult.replies().stream().anyMatch(reply -> reply == null || reply.isBlank())) {
            throw new PersonDialogueException("dialogue replies cannot contain blank text");
        }
        return safeResult;
    }

    private static String requireUserMessage(String value) {
        String normalized = Objects.requireNonNull(
                value,
                "userMessage cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("userMessage cannot be blank");
        }
        if (normalized.length() > MAX_USER_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                    "userMessage cannot exceed "
                            + MAX_USER_MESSAGE_CHARACTERS
                            + " characters"
            );
        }
        return normalized;
    }

    private static int positive(int value, String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return value;
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static void logMemoryFailure(PersonId personId, Throwable error) {
        LOGGER.warn(
                "Dialogue memory recording failed; returning generated reply: personId={}",
                personId,
                error
        );
    }
}
