package com.laishengkai.digitalperson.activity;

import com.laishengkai.digitalperson.experience.EventEndReason;
import com.laishengkai.digitalperson.experience.EventId;

import java.util.Objects;

/** Finishes one currently open digital-person event at the command time. */
public record FinishActivityCommand(
        EventId eventId,
        EventEndReason reason
) implements ActivityLifecycleCommand {
    public FinishActivityCommand {
        eventId = Objects.requireNonNull(eventId, "eventId cannot be null");
        reason = Objects.requireNonNull(reason, "reason cannot be null");
        if (reason == EventEndReason.REPLACED) {
            throw new IllegalArgumentException(
                    "REPLACED is owned by same-channel start semantics"
            );
        }
    }

    @Override
    public ActivityLifecycleAction action() {
        return ActivityLifecycleAction.FINISH;
    }
}
