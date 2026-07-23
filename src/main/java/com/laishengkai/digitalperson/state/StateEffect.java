package com.laishengkai.digitalperson.state;

import java.util.List;

/** A source of transitions applied during deterministic state settlement. */
public sealed interface StateEffect
        permits ChannelStateEffect, ResidualStateEffect, RegisteredStateEffect {
    List<StateTransition> transitions();
}
