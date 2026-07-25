package com.laishengkai.digitalperson.application;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.conversation.RecentConversationStore;
import com.laishengkai.digitalperson.dialogue.DialogueResult;
import com.laishengkai.digitalperson.dialogue.PersonDialogueException;
import com.laishengkai.digitalperson.dialogue.PersonDialogueModel;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.person.VersionedPerson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Coordinates context retrieval, direct reply generation and fail-open persistence. */
public final class PersonDialogueService {
    public static final int MAX_USER_MESSAGE_CHARACTERS = 16_000;

    private static final Logger LOGGER = LoggerFactory.getLogger(PersonDialogueService.class);

    private final PersonRepository personRepository;
    private final PersonModelContextAssembler contextAssembler;
    private final PersonDialogueModel dialogueModel;
    private final RecentConversationStore conversationStore;
    private final ConversationSummaryService summaryService;
    private final DialogueMemoryRecorder memoryRecorder;
    private final Clock clock;
    private final int maxMemoryItems;
    private final int maxConversationTurns;
    private final int conversationSummaryBatchTurns;

    /** Compatibility constructor for deployments without raw conversation persistence. */
    public PersonDialogueService(
            PersonRepository personRepository,
            PersonModelContextAssembler contextAssembler,
            PersonDialogueModel dialogueModel,
            DialogueMemoryRecorder memoryRecorder,
            Clock clock,
            int maxMemoryItems,
            int maxConversationTurns
    ) {
        this(
                personRepository,
                contextAssembler,
                dialogueModel,
                null,
                null,
                memoryRecorder,
                clock,
                maxMemoryItems,
                maxConversationTurns,
                1
        );
    }

    /** Compatibility constructor for raw persistence without rolling summarization. */
    public PersonDialogueService(
            PersonRepository personRepository,
            PersonModelContextAssembler contextAssembler,
            PersonDialogueModel dialogueModel,
            RecentConversationStore conversationStore,
            DialogueMemoryRecorder memoryRecorder,
            Clock clock,
            int maxMemoryItems,
            int maxConversationTurns
    ) {
        this(
                personRepository,
                contextAssembler,
                dialogueModel,
                conversationStore,
                null,
                memoryRecorder,
                clock,
                maxMemoryItems,
                maxConversationTurns,
                1
        );
    }

    public PersonDialogueService(
            PersonRepository personRepository,
            PersonModelContextAssembler contextAssembler,
            PersonDialogueModel dialogueModel,
            RecentConversationStore conversationStore,
            ConversationSummaryService summaryService,
            DialogueMemoryRecorder memoryRecorder,
            Clock clock,
            int maxMemoryItems,
            int maxConversationTurns,
            int conversationSummaryBatchTurns
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
        this.conversationStore = conversationStore;
        this.summaryService = summaryService;
        this.memoryRecorder = memoryRecorder;
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
        this.maxMemoryItems = positive(maxMemoryItems, "maxMemoryItems");
        this.maxConversationTurns = positive(
                maxConversationTurns,
                "maxConversationTurns"
        );
        this.conversationSummaryBatchTurns = positive(
                conversationSummaryBatchTurns,
                "conversationSummaryBatchTurns"
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
        int retrievalTurns = summaryService == null
                ? maxConversationTurns
                : Math.addExact(maxConversationTurns, conversationSummaryBatchTurns - 1);
        PersonModelContextAssemblyRequest contextRequest =
                new PersonModelContextAssemblyRequest(
                        Set.of(),
                        normalizedMessage,
                        false,
                        maxMemoryItems,
                        retrievalTurns
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

        ZoneId localTimeZone = person.getIdentity().timeZone();
        return contextStage.thenCompose(context -> {
            if (context == null) {
                throw new CompletionException(new PersonDialogueException(
                        "person dialogue context was not assembled"
                ));
            }
            logMemoryRetrieval(requestedPersonId, context.memory());
            return Objects.requireNonNull(
                    dialogueModel.reply(context, normalizedMessage),
                    "dialogueModel stage cannot be null"
            );
        }).thenCompose(result -> completeExchange(
                requestedPersonId,
                localTimeZone,
                normalizedMessage,
                requireResult(result),
                occurredAt
        ));
    }

    private CompletionStage<PersonDialogueExchange> completeExchange(
            PersonId personId,
            ZoneId localTimeZone,
            String userMessage,
            DialogueResult result,
            Instant occurredAt
    ) {
        CompletionStage<ConversationOutcome> conversationStage = persistConversation(
                personId,
                userMessage,
                result,
                occurredAt
        );
        CompletionStage<MemoryOutcome> memoryStage = recordMemory(
                personId,
                userMessage,
                result,
                occurredAt
        );
        CompletionStage<Void> summaryStage = conversationStage.thenCompose(conversation -> {
            if (conversation.status() != PersonDialogueExchange.ConversationStatus.STORED
                    || summaryService == null) {
                return CompletableFuture.completedFuture(null);
            }
            return summaryService.summarizeIfNeeded(
                    personId,
                    localTimeZone,
                    clock.instant()
            );
        });

        CompletionStage<PersonDialogueExchange> exchangeStage = conversationStage.thenCombine(
                memoryStage,
                (conversation, memory) -> new PersonDialogueExchange(
                        personId,
                        result,
                        occurredAt,
                        conversation.status(),
                        conversation.persistedTurnCount(),
                        memory.status(),
                        memory.mutationCount()
                )
        );
        return exchangeStage.thenCombine(summaryStage, (exchange, ignored) -> exchange);
    }

    private CompletionStage<ConversationOutcome> persistConversation(
            PersonId personId,
            String userMessage,
            DialogueResult result,
            Instant occurredAt
    ) {
        if (conversationStore == null) {
            return CompletableFuture.completedFuture(ConversationOutcome.disabled());
        }

        List<ConversationTurnSnapshot> turns = new ArrayList<>(result.replies().size() + 1);
        turns.add(new ConversationTurnSnapshot(
                ConversationTurnSnapshot.Role.USER,
                userMessage,
                occurredAt
        ));
        result.replies().forEach(reply -> turns.add(new ConversationTurnSnapshot(
                ConversationTurnSnapshot.Role.PERSON,
                reply,
                occurredAt
        )));
        List<ConversationTurnSnapshot> immutableTurns = List.copyOf(turns);

        final CompletionStage<Integer> stage;
        try {
            stage = Objects.requireNonNull(
                    conversationStore.append(personId, immutableTurns),
                    "conversationStore stage cannot be null"
            );
        } catch (RuntimeException error) {
            logConversationFailure(personId, error);
            return CompletableFuture.completedFuture(ConversationOutcome.failed());
        }

        return stage.handle((stored, failure) -> {
            if (failure != null) {
                logConversationFailure(personId, unwrap(failure));
                return ConversationOutcome.failed();
            }
            if (stored == null || stored != immutableTurns.size()) {
                LOGGER.warn(
                        "Dialogue conversation persistence returned an unexpected count: personId={}, expected={}, actual={}",
                        personId,
                        immutableTurns.size(),
                        stored
                );
                return ConversationOutcome.failed();
            }
            return ConversationOutcome.stored(stored);
        });
    }

    private CompletionStage<MemoryOutcome> recordMemory(
            PersonId personId,
            String userMessage,
            DialogueResult result,
            Instant occurredAt
    ) {
        if (memoryRecorder == null) {
            return CompletableFuture.completedFuture(MemoryOutcome.disabled());
        }

        final CompletionStage<List<com.laishengkai.digitalperson.memory.MemoryMutation>> stage;
        try {
            stage = Objects.requireNonNull(
                    memoryRecorder.record(personId, userMessage, result, occurredAt),
                    "memoryRecorder stage cannot be null"
            );
        } catch (RuntimeException error) {
            logMemoryFailure(personId, error);
            return CompletableFuture.completedFuture(MemoryOutcome.failed());
        }

        return stage.handle((mutations, failure) -> {
            if (failure != null) {
                logMemoryFailure(personId, unwrap(failure));
                return MemoryOutcome.failed();
            }
            List<?> safeMutations = Objects.requireNonNullElse(mutations, List.of());
            return MemoryOutcome.processed(safeMutations.size());
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

    private static void logMemoryRetrieval(PersonId personId, PersonMemoryContext memory) {
        PersonMemoryContext safeMemory = Objects.requireNonNull(
                memory,
                "memory context cannot be null"
        );
        double highestRelevance = safeMemory.items().stream()
                .mapToDouble(item -> item.relevance())
                .max()
                .orElse(0.0);
        LOGGER.info(
                "Dialogue memory retrieval completed: personId={}, availability={}, itemCount={}, highestRelevance={}",
                personId,
                safeMemory.availability(),
                safeMemory.items().size(),
                highestRelevance
        );
    }

    private static void logConversationFailure(PersonId personId, Throwable error) {
        LOGGER.warn(
                "Dialogue conversation persistence failed; returning generated reply: personId={}",
                personId,
                error
        );
    }

    private static void logMemoryFailure(PersonId personId, Throwable error) {
        LOGGER.warn(
                "Dialogue memory recording failed; returning generated reply: personId={}",
                personId,
                error
        );
    }

    private record ConversationOutcome(
            PersonDialogueExchange.ConversationStatus status,
            int persistedTurnCount
    ) {
        private static ConversationOutcome stored(int count) {
            return new ConversationOutcome(
                    PersonDialogueExchange.ConversationStatus.STORED,
                    count
            );
        }

        private static ConversationOutcome disabled() {
            return new ConversationOutcome(
                    PersonDialogueExchange.ConversationStatus.DISABLED,
                    0
            );
        }

        private static ConversationOutcome failed() {
            return new ConversationOutcome(
                    PersonDialogueExchange.ConversationStatus.FAILED,
                    0
            );
        }
    }

    private record MemoryOutcome(
            PersonDialogueExchange.MemoryStatus status,
            int mutationCount
    ) {
        private static MemoryOutcome processed(int count) {
            return new MemoryOutcome(PersonDialogueExchange.MemoryStatus.PROCESSED, count);
        }

        private static MemoryOutcome disabled() {
            return new MemoryOutcome(PersonDialogueExchange.MemoryStatus.DISABLED, 0);
        }

        private static MemoryOutcome failed() {
            return new MemoryOutcome(PersonDialogueExchange.MemoryStatus.FAILED, 0);
        }
    }
}
