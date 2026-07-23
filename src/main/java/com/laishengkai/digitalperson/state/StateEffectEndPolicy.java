package com.laishengkai.digitalperson.state;

/** Determines which lifecycle boundary stops a state effect. */
public enum StateEffectEndPolicy {
    /** Ends when the bound source event ends. */
    EVENT_END,
    /** Ignores source-event completion and ends at a fixed time. */
    FIXED_TIME,
    /** Ends at the earlier of source-event completion and the fixed deadline. */
    EVENT_END_OR_FIXED_TIME
}
