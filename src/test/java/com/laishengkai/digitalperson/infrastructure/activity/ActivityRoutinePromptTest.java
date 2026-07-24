package com.laishengkai.digitalperson.infrastructure.activity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ActivityRoutinePromptTest {

    @Test
    void requiresDurationRoutineAndReviewCadenceChecks() throws Exception {
        Method builder = LanguageModelPersonActivityDecisionModel.class
                .getDeclaredMethod("buildSystemMessage");
        builder.setAccessible(true);
        String prompt = (String) builder.invoke(null);

        assertTrue(prompt.contains("elapsedMinutes"));
        assertTrue(prompt.contains("durationStatus"));
        assertTrue(prompt.contains("physiology"));
        assertTrue(prompt.contains("sleepDebtMinutes"));
        assertTrue(prompt.contains("短睡眠"));
        assertTrue(prompt.contains("observation 为空"));
        assertTrue(prompt.contains("60 到 120 分钟"));
        assertTrue(prompt.contains("超过 180 分钟"));
        assertTrue(prompt.contains("用餐与睡眠"));
        assertTrue(prompt.contains("进入深夜"));
        assertTrue(prompt.contains("nextReviewMinutes 不应超过 30"));
        assertTrue(prompt.contains("不是机械硬上限"));
    }
}
