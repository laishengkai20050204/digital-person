package com.laishengkai.digitalperson;

import java.util.concurrent.CompletionStage;

/**
 * Produces a dialogue decision for a person without requiring the caller to block.
 */
@FunctionalInterface
public interface DialogueModel {

    CompletionStage<DialogueResult> generateReply(Person person, String userMessage);
}
