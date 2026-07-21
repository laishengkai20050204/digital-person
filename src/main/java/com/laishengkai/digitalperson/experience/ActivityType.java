package com.laishengkai.digitalperson.experience;

import lombok.Getter;

@Getter
public enum ActivityType {

    STUDY(ActivityMode.EXCLUSIVE),
    WORK(ActivityMode.EXCLUSIVE),
    EAT(ActivityMode.EXCLUSIVE),
    SLEEP(ActivityMode.EXCLUSIVE),
    REST(ActivityMode.EXCLUSIVE),
    TRAVEL(ActivityMode.EXCLUSIVE),
    EXERCISE(ActivityMode.EXCLUSIVE),
    SOCIAL(ActivityMode.EXCLUSIVE),
    ENTERTAINMENT(ActivityMode.EXCLUSIVE),
    SHOPPING(ActivityMode.EXCLUSIVE),
    CHAT(ActivityMode.CONCURRENT),
    LISTEN_MUSIC(ActivityMode.CONCURRENT),
    OTHER(ActivityMode.EXCLUSIVE);

    private final ActivityMode mode;

    ActivityType(ActivityMode mode) {
        this.mode = mode;
    }

    public boolean isExclusive() {
        return mode == ActivityMode.EXCLUSIVE;
    }

    public boolean isConcurrent() {
        return mode == ActivityMode.CONCURRENT;
    }
}
