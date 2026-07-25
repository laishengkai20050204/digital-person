package com.laishengkai.digitalperson.conversation;

import com.laishengkai.digitalperson.person.PersonId;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Application-owned port for retrieving one persisted rolling conversation summary. */
@FunctionalInterface
public interface ConversationSummaryGateway {
    CompletionStage<Optional<ConversationSummarySnapshot>> retrieve(PersonId personId);
}
