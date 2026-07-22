package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.conversation.ConversationTurnSnapshot;
import com.laishengkai.digitalperson.experience.PersonEventSnapshot;
import com.laishengkai.digitalperson.memory.MemoryItem;
import com.laishengkai.digitalperson.memory.MemorySection;
import com.laishengkai.digitalperson.memory.PersonMemoryContext;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.personality.PersonalitySnapshot;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEvaluationContext;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Fixed synthetic contexts used only by the protected internal diagnostic API. */
final class StateEvaluationDiagnosticScenarios {

    private static final PersonId TEST_PERSON_ID = PersonId.parse(
            "00000000-0000-0000-0000-000000000001"
    );

    private static final List<Scenario> SCENARIOS = List.of(
            romanticReassurance(),
            examDeadlinePressure(),
            mealAfterHunger(),
            lateNightSleepLoss(),
            exerciseCompleted(),
            friendConflict(),
            examSuccess(),
            plansCancelled(),
            socialInvitation(),
            backgroundSyncNoEffect()
    );

    private StateEvaluationDiagnosticScenarios() {
    }

    static List<Scenario> all() {
        return SCENARIOS;
    }

    static Optional<Scenario> find(String id) {
        String normalized = id == null ? "" : id.strip();
        return SCENARIOS.stream()
                .filter(scenario -> scenario.id().equals(normalized))
                .findFirst();
    }

    private static Scenario romanticReassurance() {
        Instant evaluationTime = time("2026-07-22T12:00:00Z");
        return scenario(
                "romantic-reassurance",
                "恋人回来并表达陪伴",
                "此前数小时没有联系；恋人回来表达想念、爱意和今晚陪伴。",
                new PersonalitySnapshot(0.75, 0.85, 0.45, 0.80, 0.70, 0.65),
                new PersonStateSnapshot(
                        -0.45, 0.50, 0.70, 0.35, 0.50, 0.45,
                        0.30, 0.20, 0.20, 0.85, 0.90
                ),
                event(
                        PersonEventSnapshot.Owner.USER,
                        "romantic-reassurance-new",
                        "CHAT",
                        "SOCIAL",
                        "Romantic partner sends a reassuring affectionate message",
                        "online",
                        "2026-07-22T11:59:00Z",
                        null,
                        List.of("romantic partner"),
                        "The user says they are here now, missed her too, love her, "
                                + "and want to spend the evening talking together.",
                        null
                ),
                List.of(event(
                        PersonEventSnapshot.Owner.PERSON,
                        "romantic-reassurance-active",
                        "STUDY",
                        "PERSONAL",
                        "Studying alone in the dormitory",
                        "dormitory",
                        "2026-07-22T10:00:00Z",
                        null,
                        List.of(),
                        "She has been missing the user while trying to study.",
                        null
                )),
                List.of(event(
                        PersonEventSnapshot.Owner.USER,
                        "romantic-reassurance-recent",
                        "UNAVAILABLE",
                        "SOCIAL",
                        "User was unavailable for several hours",
                        "online",
                        "2026-07-22T08:00:00Z",
                        "2026-07-22T11:55:00Z",
                        List.of("romantic partner"),
                        "The temporary silence had increased her loneliness and tension.",
                        "COMPLETED"
                )),
                memory(
                        item(
                                "relationship-romantic",
                                MemorySection.RELATIONSHIP,
                                "They are stable romantic partners. Warm reassurance from the "
                                        + "user restores her sense of security and closeness.",
                                1.0
                        ),
                        item(
                                "commitment-romantic",
                                MemorySection.COMMITMENT,
                                "They had planned to spend this evening talking together.",
                                0.95
                        )
                ),
                List.of(
                        turn(
                                ConversationTurnSnapshot.Role.PERSON,
                                "I missed you today and was starting to feel a little lonely.",
                                "2026-07-22T11:58:00Z"
                        ),
                        turn(
                                ConversationTurnSnapshot.Role.USER,
                                "I'm here now. I missed you too, I love you, and I want to "
                                        + "spend the evening talking with you.",
                                "2026-07-22T11:59:00Z"
                        )
                ),
                evaluationTime,
                false,
                expected(StateDimension.VALENCE, Direction.INCREASE),
                expected(StateDimension.TENSION, Direction.DECREASE),
                expected(StateDimension.LONELINESS, Direction.DECREASE),
                expected(StateDimension.SOCIAL_NEED, Direction.DECREASE)
        );
    }

    private static Scenario examDeadlinePressure() {
        Instant evaluationTime = time("2026-07-22T12:00:00Z");
        return scenario(
                "exam-deadline-pressure",
                "考试突然提前",
                "老师通知考试提前到明天，而她仍有大量内容没有复习。",
                new PersonalitySnapshot(0.78, 0.65, 0.40, 0.72, 0.92, 0.70),
                new PersonStateSnapshot(
                        0.10, 0.65, 0.35, 0.55, 0.45, 0.70,
                        0.30, 0.20, 0.25, 0.20, 0.25
                ),
                event(
                        PersonEventSnapshot.Owner.PERSON,
                        "exam-deadline-new",
                        "ANNOUNCEMENT",
                        "ACADEMIC",
                        "Professor moves the exam to tomorrow morning",
                        "class group chat",
                        "2026-07-22T11:58:00Z",
                        null,
                        List.of("professor", "classmates"),
                        "Three chapters remain unfinished and the exam was previously expected "
                                + "two days later.",
                        null
                ),
                List.of(event(
                        PersonEventSnapshot.Owner.PERSON,
                        "exam-deadline-active",
                        "STUDY",
                        "PERSONAL",
                        "Reviewing lecture notes",
                        "library",
                        "2026-07-22T10:30:00Z",
                        null,
                        List.of(),
                        "She was following a relaxed two-day review plan.",
                        null
                )),
                List.of(),
                memory(item(
                        "goal-exam",
                        MemorySection.GOAL,
                        "She strongly wants to pass this course with a high grade and usually "
                                + "responds to deadlines by increasing effort.",
                        0.98
                )),
                List.of(turn(
                        ConversationTurnSnapshot.Role.PERSON,
                        "I still have three chapters left, but I thought I had two more days.",
                        "2026-07-22T11:57:00Z"
                )),
                evaluationTime,
                false,
                expected(StateDimension.TENSION, Direction.INCREASE),
                expected(StateDimension.MENTAL_LOAD, Direction.INCREASE)
        );
    }

    private static Scenario mealAfterHunger() {
        Instant evaluationTime = time("2026-07-22T12:30:00Z");
        return scenario(
                "meal-after-hunger",
                "饿了很久后吃完一顿饭",
                "因为跳过午饭而非常饥饿，随后吃完温热且足量的正餐。",
                new PersonalitySnapshot(0.70, 0.55, 0.50, 0.72, 0.68, 0.60),
                new PersonStateSnapshot(
                        -0.15, 0.35, 0.30, 0.30, 0.35, 0.40,
                        0.40, 0.20, 0.90, 0.25, 0.25
                ),
                event(
                        PersonEventSnapshot.Owner.PERSON,
                        "meal-after-hunger-new",
                        "MEAL",
                        "PERSONAL",
                        "Finished a warm balanced meal after skipping lunch",
                        "campus cafeteria",
                        "2026-07-22T12:10:00Z",
                        "2026-07-22T12:28:00Z",
                        List.of(),
                        "The meal was filling, enjoyable, and she has finished eating.",
                        "COMPLETED"
                ),
                List.of(),
                List.of(event(
                        PersonEventSnapshot.Owner.PERSON,
                        "meal-after-hunger-recent",
                        "STUDY",
                        "PERSONAL",
                        "Long study session without lunch",
                        "library",
                        "2026-07-22T08:00:00Z",
                        "2026-07-22T12:00:00Z",
                        List.of(),
                        "Hunger had made it hard to concentrate.",
                        "COMPLETED"
                )),
                memory(item(
                        "preference-meal",
                        MemorySection.PREFERENCE,
                        "She finds warm savory meals comforting when tired or hungry.",
                        0.80
                )),
                List.of(),
                evaluationTime,
                false,
                expected(StateDimension.HUNGER, Direction.DECREASE),
                expected(StateDimension.VALENCE, Direction.INCREASE),
                expected(StateDimension.ENERGY, Direction.INCREASE)
        );
    }

    private static Scenario lateNightSleepLoss() {
        Instant evaluationTime = time("2026-07-23T00:00:00Z");
        return scenario(
                "late-night-sleep-loss",
                "只睡三小时就被闹钟叫醒",
                "熬夜到凌晨后只睡了三小时，早课闹钟响起。",
                new PersonalitySnapshot(0.72, 0.60, 0.42, 0.70, 0.78, 0.62),
                new PersonStateSnapshot(
                        -0.10, 0.32, 0.40, 0.35, 0.45, 0.45,
                        0.65, 0.75, 0.20, 0.20, 0.20
                ),
                event(
                        PersonEventSnapshot.Owner.PERSON,
                        "late-night-sleep-new",
                        "WAKE_UP",
                        "PERSONAL",
                        "Alarm rings for an early class after only three hours of sleep",
                        "dormitory",
                        "2026-07-23T00:00:00Z",
                        null,
                        List.of(),
                        "She went to sleep at 21:00Z after staying up late to finish work.",
                        null
                ),
                List.of(),
                List.of(event(
                        PersonEventSnapshot.Owner.PERSON,
                        "late-night-sleep-recent",
                        "WORK",
                        "PERSONAL",
                        "Stayed up late finishing an assignment",
                        "dormitory",
                        "2026-07-22T16:00:00Z",
                        "2026-07-22T21:00:00Z",
                        List.of(),
                        "The work was mentally demanding.",
                        "COMPLETED"
                )),
                memory(item(
                        "routine-sleep",
                        MemorySection.ROUTINE,
                        "She normally needs around eight hours of sleep to feel alert in class.",
                        0.95
                )),
                List.of(),
                evaluationTime,
                false,
                expected(StateDimension.SLEEPINESS, Direction.INCREASE),
                expected(StateDimension.FATIGUE, Direction.INCREASE),
                expected(StateDimension.ENERGY, Direction.DECREASE),
                expected(StateDimension.FOCUS, Direction.DECREASE)
        );
    }

    private static Scenario exerciseCompleted() {
        Instant evaluationTime = time("2026-07-22T13:00:00Z");
        return scenario(
                "exercise-completed",
                "完成一次跑步",
                "心情烦躁时完成了三十五分钟跑步和放松。",
                new PersonalitySnapshot(0.68, 0.58, 0.62, 0.70, 0.72, 0.66),
                new PersonStateSnapshot(
                        -0.10, 0.55, 0.65, 0.40, 0.45, 0.45,
                        0.25, 0.15, 0.25, 0.30, 0.30
                ),
                event(
                        PersonEventSnapshot.Owner.PERSON,
                        "exercise-completed-new",
                        "EXERCISE",
                        "PERSONAL",
                        "Completed a thirty-five-minute run and cool-down",
                        "campus track",
                        "2026-07-22T12:20:00Z",
                        "2026-07-22T12:58:00Z",
                        List.of(),
                        "The pace was challenging but manageable, and she feels physically warm.",
                        "COMPLETED"
                ),
                List.of(),
                List.of(event(
                        PersonEventSnapshot.Owner.PERSON,
                        "exercise-completed-recent",
                        "WORK",
                        "PERSONAL",
                        "Frustrating afternoon task",
                        "dormitory",
                        "2026-07-22T10:00:00Z",
                        "2026-07-22T12:00:00Z",
                        List.of(),
                        "The task had raised tension and lowered mood.",
                        "COMPLETED"
                )),
                memory(item(
                        "routine-exercise",
                        MemorySection.ROUTINE,
                        "Running usually helps her release tension and improves her mood, "
                                + "though it causes short-term physical tiredness.",
                        0.92
                )),
                List.of(),
                evaluationTime,
                false,
                expected(StateDimension.VALENCE, Direction.INCREASE),
                expected(StateDimension.TENSION, Direction.DECREASE),
                expected(StateDimension.FATIGUE, Direction.INCREASE)
        );
    }

    private static Scenario friendConflict() {
        Instant evaluationTime = time("2026-07-22T14:00:00Z");
        return scenario(
                "friend-conflict",
                "亲密朋友发来指责消息",
                "一次误会后，亲密朋友发来语气强烈且带指责的消息。",
                new PersonalitySnapshot(0.82, 0.82, 0.48, 0.76, 0.72, 0.68),
                new PersonStateSnapshot(
                        0.15, 0.55, 0.30, 0.50, 0.35, 0.55,
                        0.25, 0.15, 0.20, 0.25, 0.30
                ),
                event(
                        PersonEventSnapshot.Owner.USER,
                        "friend-conflict-new",
                        "MESSAGE",
                        "SOCIAL",
                        "Close friend sends an accusatory message after a misunderstanding",
                        "online",
                        "2026-07-22T13:59:00Z",
                        null,
                        List.of("close friend"),
                        "The friend says she was selfish and cannot be relied on. The issue has "
                                + "not yet been discussed calmly.",
                        null
                ),
                List.of(),
                List.of(event(
                        PersonEventSnapshot.Owner.PERSON,
                        "friend-conflict-recent",
                        "SOCIAL",
                        "SOCIAL",
                        "Minor scheduling misunderstanding with a close friend",
                        "online",
                        "2026-07-22T12:30:00Z",
                        "2026-07-22T12:40:00Z",
                        List.of("close friend"),
                        "She believed the issue was small and temporary.",
                        "COMPLETED"
                )),
                memory(item(
                        "relationship-friend",
                        MemorySection.RELATIONSHIP,
                        "This friendship is important to her, and harsh criticism from close "
                                + "friends affects her strongly.",
                        0.97
                )),
                List.of(turn(
                        ConversationTurnSnapshot.Role.USER,
                        "You only think about yourself. I don't think I can rely on you.",
                        "2026-07-22T13:59:00Z"
                )),
                evaluationTime,
                false,
                expected(StateDimension.VALENCE, Direction.DECREASE),
                expected(StateDimension.TENSION, Direction.INCREASE),
                expected(StateDimension.LONELINESS, Direction.INCREASE)
        );
    }

    private static Scenario examSuccess() {
        Instant evaluationTime = time("2026-07-22T15:00:00Z");
        return scenario(
                "exam-success",
                "收到高分成绩",
                "为成绩担心多日后，查到考试得分九十四分。",
                new PersonalitySnapshot(0.76, 0.66, 0.46, 0.74, 0.90, 0.68),
                new PersonStateSnapshot(
                        -0.05, 0.50, 0.60, 0.45, 0.50, 0.50,
                        0.30, 0.20, 0.20, 0.25, 0.25
                ),
                event(
                        PersonEventSnapshot.Owner.PERSON,
                        "exam-success-new",
                        "RESULT",
                        "ACADEMIC",
                        "Received a score of ninety-four on a difficult exam",
                        "student portal",
                        "2026-07-22T14:59:00Z",
                        null,
                        List.of(),
                        "She had worried for several days that she might have performed poorly.",
                        null
                ),
                List.of(),
                List.of(event(
                        PersonEventSnapshot.Owner.PERSON,
                        "exam-success-recent",
                        "WAITING",
                        "PERSONAL",
                        "Waiting for the exam result",
                        "online",
                        "2026-07-19T00:00:00Z",
                        "2026-07-22T14:59:00Z",
                        List.of(),
                        "Uncertainty had maintained moderate tension.",
                        "COMPLETED"
                )),
                memory(item(
                        "goal-academic-success",
                        MemorySection.GOAL,
                        "Academic progress matters to her and successful results reinforce her "
                                + "motivation to continue studying.",
                        0.96
                )),
                List.of(),
                evaluationTime,
                false,
                expected(StateDimension.VALENCE, Direction.INCREASE),
                expected(StateDimension.TENSION, Direction.DECREASE),
                expected(StateDimension.MOTIVATION, Direction.INCREASE)
        );
    }

    private static Scenario plansCancelled() {
        Instant evaluationTime = time("2026-07-22T16:00:00Z");
        return scenario(
                "plans-cancelled",
                "期待已久的计划被临时取消",
                "准备出门前，朋友临时取消了期待一周的周末活动。",
                new PersonalitySnapshot(0.74, 0.72, 0.56, 0.78, 0.68, 0.64),
                new PersonStateSnapshot(
                        0.45, 0.65, 0.20, 0.50, 0.30, 0.72,
                        0.20, 0.10, 0.20, 0.30, 0.35
                ),
                event(
                        PersonEventSnapshot.Owner.USER,
                        "plans-cancelled-new",
                        "CANCELLATION",
                        "SOCIAL",
                        "Friend cancels a long-awaited weekend outing at the last minute",
                        "online",
                        "2026-07-22T15:58:00Z",
                        null,
                        List.of("friend"),
                        "The friend has a legitimate schedule conflict, but there is no "
                                + "replacement plan yet.",
                        null
                ),
                List.of(event(
                        PersonEventSnapshot.Owner.PERSON,
                        "plans-cancelled-active",
                        "PREPARATION",
                        "PERSONAL",
                        "Getting ready to leave for the outing",
                        "dormitory",
                        "2026-07-22T15:30:00Z",
                        null,
                        List.of(),
                        "She had already finished getting ready and felt excited.",
                        null
                )),
                List.of(),
                memory(item(
                        "plan-weekend-outing",
                        MemorySection.PLAN,
                        "She had looked forward to this outing throughout a stressful week.",
                        0.98
                )),
                List.of(turn(
                        ConversationTurnSnapshot.Role.USER,
                        "I'm really sorry, something came up and I can't go today.",
                        "2026-07-22T15:58:00Z"
                )),
                evaluationTime,
                false,
                expected(StateDimension.VALENCE, Direction.DECREASE),
                expected(StateDimension.MOTIVATION, Direction.DECREASE),
                expected(StateDimension.LONELINESS, Direction.INCREASE)
        );
    }

    private static Scenario socialInvitation() {
        Instant evaluationTime = time("2026-07-22T17:00:00Z");
        return scenario(
                "social-invitation",
                "独处时收到朋友晚餐邀请",
                "独自学习一下午后，朋友主动邀请她一起吃晚餐。",
                new PersonalitySnapshot(0.70, 0.62, 0.65, 0.78, 0.70, 0.66),
                new PersonStateSnapshot(
                        -0.10, 0.48, 0.35, 0.42, 0.40, 0.45,
                        0.32, 0.20, 0.35, 0.65, 0.75
                ),
                event(
                        PersonEventSnapshot.Owner.USER,
                        "social-invitation-new",
                        "INVITATION",
                        "SOCIAL",
                        "Friend invites her to have dinner together tonight",
                        "online",
                        "2026-07-22T16:59:00Z",
                        null,
                        List.of("friend"),
                        "The friend says they have missed her and are looking forward to seeing her.",
                        null
                ),
                List.of(event(
                        PersonEventSnapshot.Owner.PERSON,
                        "social-invitation-active",
                        "STUDY",
                        "PERSONAL",
                        "Studying alone for several hours",
                        "library",
                        "2026-07-22T13:00:00Z",
                        null,
                        List.of(),
                        "The prolonged solitude had increased social need.",
                        null
                )),
                List.of(),
                memory(item(
                        "relationship-dinner-friend",
                        MemorySection.RELATIONSHIP,
                        "She feels comfortable with this friend and usually enjoys their dinners.",
                        0.90
                )),
                List.of(turn(
                        ConversationTurnSnapshot.Role.USER,
                        "Come have dinner with me tonight. I've missed you and really want to see you.",
                        "2026-07-22T16:59:00Z"
                )),
                evaluationTime,
                false,
                expected(StateDimension.VALENCE, Direction.INCREASE),
                expected(StateDimension.LONELINESS, Direction.DECREASE),
                expected(StateDimension.SOCIAL_NEED, Direction.DECREASE)
        );
    }

    private static Scenario backgroundSyncNoEffect() {
        Instant evaluationTime = time("2026-07-22T18:00:00Z");
        return scenario(
                "background-sync-no-effect",
                "未被察觉的后台同步",
                "手机静默完成后台云同步，没有提示，人物没有察觉。",
                new PersonalitySnapshot(0.70, 0.60, 0.50, 0.70, 0.70, 0.65),
                new PersonStateSnapshot(
                        0.10, 0.55, 0.25, 0.60, 0.35, 0.55,
                        0.25, 0.15, 0.25, 0.25, 0.25
                ),
                event(
                        PersonEventSnapshot.Owner.PERSON,
                        "background-sync-new",
                        "DEVICE_BACKGROUND_TASK",
                        "SYSTEM",
                        "Phone silently completes a background cloud sync",
                        "phone",
                        "2026-07-22T17:59:00Z",
                        "2026-07-22T17:59:05Z",
                        List.of(),
                        "There was no sound, vibration, notification, interruption, or awareness "
                                + "of the event.",
                        "COMPLETED"
                ),
                List.of(),
                List.of(),
                PersonMemoryContext.available(List.of()),
                List.of(),
                evaluationTime,
                true
        );
    }

    private static Scenario scenario(
            String id,
            String title,
            String description,
            PersonalitySnapshot personality,
            PersonStateSnapshot state,
            PersonEventSnapshot newEvent,
            List<PersonEventSnapshot> activeEvents,
            List<PersonEventSnapshot> recentEvents,
            PersonMemoryContext memory,
            List<ConversationTurnSnapshot> recentConversation,
            Instant evaluationTime,
            boolean expectsNoMaterialEffect,
            ExpectedTransition... expectations
    ) {
        return new Scenario(
                id,
                title,
                description,
                new StateEvaluationContext(
                        TEST_PERSON_ID,
                        personality,
                        state,
                        newEvent,
                        activeEvents,
                        recentEvents,
                        memory,
                        recentConversation,
                        evaluationTime
                ),
                List.of(expectations),
                expectsNoMaterialEffect
        );
    }

    private static PersonEventSnapshot event(
            PersonEventSnapshot.Owner owner,
            String eventId,
            String activityType,
            String channel,
            String title,
            String location,
            String startTime,
            String endTime,
            List<String> participants,
            String notes,
            String endReason
    ) {
        return new PersonEventSnapshot(
                owner,
                eventId,
                activityType,
                channel,
                title,
                location,
                time(startTime),
                endTime == null ? null : time(endTime),
                participants,
                notes,
                endReason
        );
    }

    private static PersonMemoryContext memory(MemoryItem... items) {
        return PersonMemoryContext.available(List.of(items));
    }

    private static MemoryItem item(
            String id,
            MemorySection section,
            String content,
            double relevance
    ) {
        return new MemoryItem(
                id,
                section,
                content,
                relevance,
                time("2026-07-01T00:00:00Z"),
                time("2026-07-21T00:00:00Z")
        );
    }

    private static ConversationTurnSnapshot turn(
            ConversationTurnSnapshot.Role role,
            String text,
            String occurredAt
    ) {
        return new ConversationTurnSnapshot(role, text, time(occurredAt));
    }

    private static ExpectedTransition expected(
            StateDimension dimension,
            Direction direction
    ) {
        return new ExpectedTransition(dimension, direction);
    }

    private static Instant time(String value) {
        return Instant.parse(value);
    }

    record Scenario(
            String id,
            String title,
            String description,
            StateEvaluationContext context,
            List<ExpectedTransition> expectations,
            boolean expectsNoMaterialEffect
    ) {
        Scenario {
            id = requireText(id, "id");
            title = requireText(title, "title");
            description = requireText(description, "description");
            context = Objects.requireNonNull(context, "context cannot be null");
            expectations = List.copyOf(Objects.requireNonNull(
                    expectations,
                    "expectations cannot be null"
            ));
            if (expectations.stream().anyMatch(Objects::isNull)) {
                throw new NullPointerException("expectations cannot contain null");
            }
            if (expectsNoMaterialEffect && !expectations.isEmpty()) {
                throw new IllegalArgumentException(
                        "no-effect scenario cannot also require transitions"
                );
            }
        }
    }

    record ExpectedTransition(StateDimension dimension, Direction direction) {
        ExpectedTransition {
            dimension = Objects.requireNonNull(dimension, "dimension cannot be null");
            direction = Objects.requireNonNull(direction, "direction cannot be null");
        }
    }

    enum Direction {
        INCREASE,
        DECREASE
    }

    private static String requireText(String value, String fieldName) {
        String normalized = Objects.requireNonNull(
                value,
                fieldName + " cannot be null"
        ).strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " cannot be blank");
        }
        return normalized;
    }
}
