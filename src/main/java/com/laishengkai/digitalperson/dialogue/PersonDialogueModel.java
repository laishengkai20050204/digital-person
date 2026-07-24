package com.laishengkai.digitalperson.dialogue;

import com.laishengkai.digitalperson.modelcontext.PersonModelContextSnapshot;

import java.util.concurrent.CompletionStage;

/** Provider-neutral boundary for generating one direct reply as a digital person. */
@FunctionalInterface
public interface PersonDialogueModel {
    CompletionStage<DialogueResult> reply(
            PersonModelContextSnapshot context,
            String userMessage
    );
}
