package com.laishengkai.digitalperson;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class Person {

    private static final int MAX_FUNCTION_ROUNDS = 8;

    private final Personality personality;
    private final List<LifeEvent> lifeEvents = new ArrayList<>();

    public Person(Personality personality) {
        this.personality = Objects.requireNonNull(
                personality,
                "personality cannot be null"
        );
    }

    public Personality getPersonality() {
        return personality;
    }

    public List<LifeEvent> getLifeEvents() {
        return List.copyOf(lifeEvents);
    }

    public void addLifeEvent(LifeEvent lifeEvent) {
        lifeEvents.add(Objects.requireNonNull(
                lifeEvent,
                "lifeEvent cannot be null"
        ));
    }

    public CompletionStage<DialogueResult> chatAsync(
            String userMessage,
            DialogueModel dialogueModel,
            FunctionExecutor functionExecutor
    ) {
        String message = Objects.requireNonNull(
                userMessage,
                "userMessage cannot be null"
        ).strip();
        if (message.isEmpty()) {
            throw new IllegalArgumentException("userMessage cannot be blank");
        }

        Objects.requireNonNull(dialogueModel, "dialogueModel cannot be null");
        Objects.requireNonNull(functionExecutor, "functionExecutor cannot be null");

        return continueDialogue(
                dialogueModel,
                functionExecutor,
                new DialogueModel.DialogueContext(message, List.of()),
                0
        );
    }

    private CompletionStage<DialogueResult> continueDialogue(
            DialogueModel dialogueModel,
            FunctionExecutor functionExecutor,
            DialogueModel.DialogueContext context,
            int functionRound
    ) {
        return dialogueModel.generateReply(this, context).thenCompose(result -> {
            if (!result.requiresFunctionCalls()) {
                return CompletableFuture.completedFuture(result.toFinalResult());
            }

            if (functionRound >= MAX_FUNCTION_ROUNDS) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException(
                                "Dialogue exceeded the maximum number of function-call rounds"
                        )
                );
            }

            return functionExecutor.executeAll(result.functionCalls())
                    .thenCompose(exchanges -> {
                        List<DialogueModel.FunctionExchange> history =
                                new ArrayList<>(context.functionExchanges());
                        history.addAll(exchanges);

                        return continueDialogue(
                                dialogueModel,
                                functionExecutor,
                                new DialogueModel.DialogueContext(
                                        context.userMessage(),
                                        history
                                ),
                                functionRound + 1
                        );
                    });
        });
    }
}
