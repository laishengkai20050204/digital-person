package com.laishengkai.digitalperson.state;

import java.util.List;

/** A source of transitions applied during deterministic state settlement. */
public interface StateEffect {
    List<StateTransition> transitions();
}
