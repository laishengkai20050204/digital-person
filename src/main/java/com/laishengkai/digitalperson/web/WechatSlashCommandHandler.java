package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.person.PersonId;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/** Resolves exact slash-prefixed WeChat commands without invoking the dialogue model. */
@FunctionalInterface
public interface WechatSlashCommandHandler {

    Optional<CommandResult> handle(PersonId personId, String message);

    record CommandResult(String content, Instant occurredAt) {
        public CommandResult {
            content = requireText(content);
            occurredAt = Objects.requireNonNull(occurredAt, "occurredAt cannot be null");
        }

        private static String requireText(String value) {
            String normalized = Objects.requireNonNull(
                    value,
                    "content cannot be null"
            ).strip();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("content cannot be blank");
            }
            return normalized;
        }
    }
}
