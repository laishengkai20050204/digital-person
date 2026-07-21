package com.laishengkai.digitalperson.experience;

/**
 * A concurrency lane for activities.
 *
 * <p>Activities in different channels may overlap. Only one open activity may
 * exist in the same channel at a time.
 */
public enum ActivityChannel {
    PRIMARY,
    COMMUNICATION,
    AUDIO
}
