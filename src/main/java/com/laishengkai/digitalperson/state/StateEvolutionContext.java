package com.laishengkai.digitalperson.state;

import com.laishengkai.digitalperson.experience.ActivityChannel;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable runtime state required to continue deterministic state evolution.
 *
 * <p>This object belongs to one person and should be persisted with that person.
 * {@link StateUpdater} itself remains reusable and stateless.</p>
 */
public record StateEvolutionContext(
        Instant lastUpdatedAt,
        Map<ActivityChannel, ChannelStateEffect> channelEffects
) {
    public StateEvolutionContext {
        Map<ActivityChannel, ChannelStateEffect> copy =
                new EnumMap<>(ActivityChannel.class);
        Map<ActivityChannel, ChannelStateEffect> requestedEffects =
                Objects.requireNonNull(channelEffects, "channelEffects cannot be null");

        requestedEffects.forEach((channel, effect) -> {
            Objects.requireNonNull(channel, "channel cannot be null");
            ChannelStateEffect nonNullEffect = Objects.requireNonNull(
                    effect,
                    "effect cannot be null"
            );
            if (nonNullEffect.channel() != channel) {
                throw new IllegalArgumentException("effect channel must match map key");
            }
            copy.put(channel, nonNullEffect);
        });
        channelEffects = Map.copyOf(copy);
    }

    public static StateEvolutionContext initial() {
        return new StateEvolutionContext(null, Map.of());
    }

    public Optional<Instant> previousUpdateTime() {
        return Optional.ofNullable(lastUpdatedAt);
    }
}
