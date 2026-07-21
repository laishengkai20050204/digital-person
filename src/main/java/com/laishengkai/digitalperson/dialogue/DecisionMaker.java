package com.laishengkai.digitalperson.dialogue;

import java.util.concurrent.CompletionStage;

@FunctionalInterface
public interface DecisionMaker {

    CompletionStage<DialogueDecision> decide(DialogueContext context);
}
