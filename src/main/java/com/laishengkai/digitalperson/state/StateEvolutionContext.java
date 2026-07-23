package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.EventId;

import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable runtime state required to continue deterministic state evolution.
 *
 * <p>Activity-bound effects are indexed by concurrency channel. Residual effects
 * are indexed by their source event, so they can survive event completion and
 * overlap unrelated activities and other residual effects.</p>
 */
public record StateEvolutionContext(
        Instant lastUpdatedAt,
        Map<ActivityChannel, ChannelStateEffect> channelEffects,
        Map<EventId, ResidualStateEffect> residualEffects
) {
    public StateEvolutionContext(
            Instant lastUpdatedAt,
            Map<ActivityChannel, ChannelStateEffect> channelEffects
    ) {
        this(lastUpdatedAt, channelEffects, Map.of());
    }

    public StateEvolutionContext {
        Map<ActivityChannel, ChannelStateEffect> channelCopy =
                new EnumMap<>(ActivityChannel.class);
        Map<ActivityChannel, ChannelStateEffect> requestedChannelEffects =
                Objects.requireNonNull(channelEffects, "channelEffects cannot be null");

        requestedChannelEffects.forEach((channel, effect) -> {
            Objects.requireNonNull(channel, "channel cannot be null");
            ChannelStateEffect nonNullEffect = Objects.requireNonNull(
                    effect,
                    "effect cannot be null"
            );
            if (nonNullEffect.channel() != channel) {
                throw new IllegalArgumentException("effect channel must match map key");
            }
            channelCopy.put(channel, nonNullEffect);
        });
        channelEffects = Map.copyOf(channelCopy);

        Map<EventId, ResidualStateEffect> residualCopy = new HashMap<>();
        Objects.requireNonNull(residualEffects, "residualEffects cannot be null")
                .forEach((sourceEventId, effect) -> {
                    EventId nonNullEventId = Objects.requireNonNull(
                            sourceEventId,
                            "residual effect source event id cannot be null"
                    );
                    ResidualStateEffect nonNullEffect = Objects.requireNonNull(
                            effect,
                            "residual effect cannot be null"
                    );
                    if (!nonNullEventId.equals(nonNullEffect.sourceEventId())) {
                        throw new IllegalArgumentException(
                                "residual effect source event id must match map key"
                        );
                    }
                    residualCopy.put(nonNullEventId, nonNullEffect);
                });
        residualEffects = Map.copyOf(residualCopy);
    }

    public static StateEvolutionContext initial() {
        return new StateEvolutionContext(null, Map.of(), Map.of());
    }

    public Optional<Instant> previousUpdateTime() {
        return Optional.ofNullable(lastUpdatedAt);
    }
}
