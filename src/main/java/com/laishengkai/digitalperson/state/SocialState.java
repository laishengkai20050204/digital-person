package com.laishengkai.digitalperson.state;

import lombok.Getter;
import lombok.ToString;

/**
 * Short-term social condition.
 * Every value is represented from {@code 0.0} to {@code 1.0}.
 */
@Getter
@ToString
public final class SocialState {

    private double loneliness;
    private double socialNeed;

    public SocialState(double loneliness, double socialNeed) {
        setLoneliness(loneliness);
        setSocialNeed(socialNeed);
    }

    public static SocialState baseline() {
        return new SocialState(0.0, 0.5);
    }

    public void setLoneliness(double loneliness) {
        this.loneliness = validateUnitValue(loneliness, "loneliness");
    }

    public void setSocialNeed(double socialNeed) {
        this.socialNeed = validateUnitValue(socialNeed, "socialNeed");
    }

    /**
     * Applies relative changes while keeping every dimension inside [0, 1].
     */
    public void adjust(double lonelinessDelta, double socialNeedDelta) {
        loneliness = applyDelta(loneliness, lonelinessDelta, "lonelinessDelta");
        socialNeed = applyDelta(socialNeed, socialNeedDelta, "socialNeedDelta");
    }

    private static double validateUnitValue(double value, String name) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException(
                    name + " must be a finite value between 0.0 and 1.0"
            );
        }
        return value;
    }

    private static double applyDelta(double current, double delta, String name) {
        if (!Double.isFinite(delta)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
        return Math.clamp(current + delta, 0.0, 1.0);
    }
}
