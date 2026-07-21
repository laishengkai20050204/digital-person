package com.laishengkai.digitalperson.experience;

import java.util.Objects;

public enum ActivityType {

    STUDY(ActivityChannel.PRIMARY),
    WORK(ActivityChannel.PRIMARY),
    EAT(ActivityChannel.PRIMARY),
    SLEEP(ActivityChannel.PRIMARY),
    REST(ActivityChannel.PRIMARY),
    TRAVEL(ActivityChannel.PRIMARY),
    EXERCISE(ActivityChannel.PRIMARY),
    SOCIAL(ActivityChannel.PRIMARY),
    ENTERTAINMENT(ActivityChannel.PRIMARY),
    SHOPPING(ActivityChannel.PRIMARY),

    CHAT(ActivityChannel.COMMUNICATION),
    LISTEN_MUSIC(ActivityChannel.AUDIO),

    OTHER(ActivityChannel.PRIMARY);

    private final ActivityChannel channel;

    ActivityType(ActivityChannel channel) {
        this.channel = Objects.requireNonNull(channel, "channel cannot be null");
    }

    public ActivityChannel getChannel() {
        return channel;
    }

    public boolean conflictsWith(ActivityType other) {
        Objects.requireNonNull(other, "other cannot be null");
        return channel == other.channel;
    }
}
