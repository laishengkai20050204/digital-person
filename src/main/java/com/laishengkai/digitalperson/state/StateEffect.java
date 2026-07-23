package com.laishengkai.digitalperson.state;

import java.util.List;

/** A time-bounded source of transitions applied during deterministic state settlement. */
public sealed interface StateEffect permits ChannelStateEffect, ResidualStateEffect {
    List<StateTransition> transitions();
}
