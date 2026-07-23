package com.laishengkai.digitalperson.activity;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.EventId;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable event-lifecycle plan returned by one autonomous activity decision. */
public record PersonActivityDecisionPlan(
        List<ActivityLifecycleCommand> commands,
        int nextReviewMinutes
) {
    public static final int MAX_COMMANDS = 6;
    public static final int MIN_NEXT_REVIEW_MINUTES = 1;
    public static final int MAX_NEXT_REVIEW_MINUTES = 360;

    public PersonActivityDecisionPlan {
        commands = List.copyOf(Objects.requireNonNull(
                commands,
                "commands cannot be null"
        ));
        if (commands.stream().anyMatch(Objects::isNull)) {
            throw new NullPointerException("commands cannot contain null");
        }
        if (commands.size() > MAX_COMMANDS) {
            throw new IllegalArgumentException(
                    "commands cannot contain more than " + MAX_COMMANDS + " values"
            );
        }
        if (nextReviewMinutes < MIN_NEXT_REVIEW_MINUTES
                || nextReviewMinutes > MAX_NEXT_REVIEW_MINUTES) {
            throw new IllegalArgumentException(
                    "nextReviewMinutes must be between "
                            + MIN_NEXT_REVIEW_MINUTES
                            + " and "
                            + MAX_NEXT_REVIEW_MINUTES
            );
        }
        validateUniqueness(commands);
    }

    public static PersonActivityDecisionPlan unchanged(int nextReviewMinutes) {
        return new PersonActivityDecisionPlan(List.of(), nextReviewMinutes);
    }

    public List<FinishActivityCommand> finishCommands() {
        return commands.stream()
                .filter(FinishActivityCommand.class::isInstance)
                .map(FinishActivityCommand.class::cast)
                .toList();
    }

    public List<StartActivityCommand> startCommands() {
        return commands.stream()
                .filter(StartActivityCommand.class::isInstance)
                .map(StartActivityCommand.class::cast)
                .toList();
    }

    private static void validateUniqueness(List<ActivityLifecycleCommand> commands) {
        Set<EventId> finishedEventIds = new HashSet<>();
        Set<ActivityChannel> startedChannels = EnumSet.noneOf(ActivityChannel.class);
        for (ActivityLifecycleCommand command : commands) {
            switch (command) {
                case FinishActivityCommand finish -> {
                    if (!finishedEventIds.add(finish.eventId())) {
                        throw new IllegalArgumentException(
                                "an event can only be finished once per plan: "
                                        + finish.eventId()
                        );
                    }
                }
                case StartActivityCommand start -> {
                    if (!startedChannels.add(start.channel())) {
                        throw new IllegalArgumentException(
                                "only one activity may be started per channel: "
                                        + start.channel()
                        );
                    }
                }
            }
        }
    }
}
