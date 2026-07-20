package com.laishengkai.digitalperson;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class Person {

    private static final int MAX_FUNCTION_ROUNDS = 8;

    private final Personality personality;
    private final List<LifeEvent> lifeEvents;

    public Person(Personality personality) {
        this.personality = Objects.requireNonNull(personality, "personality cannot be null");
        this.lifeEvents = new ArrayList<>();
    }

    public Personality getPersonality() {
        return personality;
    }

    public List<LifeEvent> getLifeEvents() {
        return Collections.unmodifiableList(lifeEvents);
    }

    public void addLifeEvent(LifeEvent lifeEvent) {
        lifeEvents.add(Objects.requireNonNull(lifeEvent, "lifeEvent cannot be null"));
    }

    /**
     * Starts an asynchronous dialogue turn and returns only the final user-facing result.
     *
     * <p>If the model requests functions, this method executes them internally, sends their
     * results back to the model, and continues until the model produces reply text or deliberately
     * chooses not to reply.
     */
    public CompletionStage<DialogueResult> chatAsync(
            String userMessage,
            DialogueModel dialogueModel,
            FunctionExecutor functionExecutor
    ) {
        String normalizedMessage = Objects.requireNonNull(
                userMessage,
                "userMessage cannot be null"
        ).strip();
        if (normalizedMessage.isEmpty()) {
            throw new IllegalArgumentException("userMessage cannot be blank");
        }

        Objects.requireNonNull(dialogueModel, "dialogueModel cannot be null");
        Objects.requireNonNull(functionExecutor, "functionExecutor cannot be null");

        DialogueModel.DialogueContext context = new DialogueModel.DialogueContext(
                normalizedMessage,
                List.of()
        );

        return continueDialogue(dialogueModel, functionExecutor, context, 0);
    }

    private CompletionStage<DialogueResult> continueDialogue(
            DialogueModel dialogueModel,
            FunctionExecutor functionExecutor,
            DialogueModel.DialogueContext context,
            int functionRound
    ) {
        CompletionStage<DialogueModelResult> modelStage = Objects.requireNonNull(
                dialogueModel.generateReply(this, context),
                "dialogueModel cannot return a null CompletionStage"
        );

        return modelStage.thenCompose(modelResult -> {
            DialogueModelResult normalizedResult = Objects.requireNonNull(
                    modelResult,
                    "dialogueModel cannot produce a null result"
            );

            if (!normalizedResult.requiresFunctionCalls()) {
                return CompletableFuture.completedFuture(
                        normalizedResult.toFinalResult()
                );
            }

            if (functionRound >= MAX_FUNCTION_ROUNDS) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Dialogue exceeded the maximum number of function-call rounds"
                        )
                );
            }

            return executeFunctionCalls(
                    normalizedResult.functionCalls(),
                    functionExecutor
            ).thenCompose(newExchanges -> {
                List<DialogueModel.FunctionExchange> allExchanges = new ArrayList<>(
                        context.functionExchanges()
                );
                allExchanges.addAll(newExchanges);

                DialogueModel.DialogueContext nextContext =
                        new DialogueModel.DialogueContext(
                                context.userMessage(),
                                allExchanges
                        );

                return continueDialogue(
                        dialogueModel,
                        functionExecutor,
                        nextContext,
                        functionRound + 1
                );
            });
        });
    }

    private CompletionStage<List<DialogueModel.FunctionExchange>> executeFunctionCalls(
            List<DialogueModelResult.FunctionCall> functionCalls,
            FunctionExecutor functionExecutor
    ) {
        CompletionStage<List<DialogueModel.FunctionExchange>> stage =
                CompletableFuture.completedFuture(List.of());

        for (DialogueModelResult.FunctionCall functionCall : functionCalls) {
            stage = stage.thenCompose(exchanges -> {
                CompletionStage<FunctionExecutor.FunctionResult> executionStage =
                        Objects.requireNonNull(
                                functionExecutor.execute(functionCall),
                                "functionExecutor cannot return a null CompletionStage"
                        );

                return executionStage.thenApply(functionResult -> {
                    DialogueModel.FunctionExchange exchange =
                            new DialogueModel.FunctionExchange(
                                    functionCall,
                                    functionResult
                            );

                    List<DialogueModel.FunctionExchange> updated =
                            new ArrayList<>(exchanges);
                    updated.add(exchange);
                    return List.copyOf(updated);
                });
            });
        }

        return stage;
    }
}
