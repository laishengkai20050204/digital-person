package com.laishengkai.digitalperson.web;

import com.laishengkai.digitalperson.application.PersonCurrentStateProjector;
import com.laishengkai.digitalperson.application.PersonNotFoundException;
import com.laishengkai.digitalperson.experience.ActivityChannel;
import com.laishengkai.digitalperson.experience.ActivityType;
import com.laishengkai.digitalperson.experience.EventId;
import com.laishengkai.digitalperson.experience.PersonEvent;
import com.laishengkai.digitalperson.person.Person;
import com.laishengkai.digitalperson.person.PersonId;
import com.laishengkai.digitalperson.person.PersonRepository;
import com.laishengkai.digitalperson.state.PersonStateSnapshot;
import com.laishengkai.digitalperson.state.RegisteredStateEffect;
import com.laishengkai.digitalperson.state.StateDimension;
import com.laishengkai.digitalperson.state.StateEffectType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Handles read-only, namespaced commands sent through the OpenClaw WeChat channel. */
@Component
@ConditionalOnProperty(
        prefix = "digital-person.openai-compat",
        name = "enabled",
        havingValue = "true"
)
public final class WechatSlashCommandService implements WechatSlashCommandHandler {
    private static final String COMMAND_NAMESPACE = "#dp";
    private static final Duration ACTIVITY_HISTORY_WINDOW = Duration.ofHours(24);
    private static final String HELP = """
            可用人物指令
            #dp activity     查看当前活动
            #dp activity24h  查看过去24小时活动
            #dp state        查看完整状态
            #dp status       查看活动和主要状态
            #dp effects      查看生效中的状态效果
            #dp help         查看指令帮助
            """;

    private static final DateTimeFormatter TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.SIMPLIFIED_CHINESE);

    private final PersonRepository personRepository;
    private final PersonCurrentStateProjector stateProjector;
    private final Clock clock;

    @Autowired
    public WechatSlashCommandService(
            PersonRepository personRepository,
            PersonCurrentStateProjector stateProjector
    ) {
        this(personRepository, stateProjector, Clock.systemUTC());
    }

    WechatSlashCommandService(
            PersonRepository personRepository,
            PersonCurrentStateProjector stateProjector,
            Clock clock
    ) {
        this.personRepository = Objects.requireNonNull(
                personRepository,
                "personRepository cannot be null"
        );
        this.stateProjector = Objects.requireNonNull(
                stateProjector,
                "stateProjector cannot be null"
        );
        this.clock = Objects.requireNonNull(clock, "clock cannot be null");
    }

    @Override
    public Optional<CommandResult> handle(PersonId personId, String message) {
        PersonId requestedPersonId = Objects.requireNonNull(
                personId,
                "personId cannot be null"
        );
        String normalized = Objects.requireNonNull(
                message,
                "message cannot be null"
        ).strip();
        Optional<String> parsedCommand = parseCommand(normalized);
        if (parsedCommand.isEmpty()) {
            return Optional.empty();
        }

        Instant now = clock.instant();
        String command = parsedCommand.orElseThrow();
        if (command.isEmpty() || "help".equals(command)) {
            return Optional.of(new CommandResult(HELP, now));
        }
        if (!isKnownCommand(command)) {
            return Optional.of(new CommandResult(
                    "未知人物指令：" + normalized + "\n发送 #dp help 查看可用指令。",
                    now
            ));
        }

        Person person = personRepository.findById(requestedPersonId)
                .orElseThrow(() -> new PersonNotFoundException(requestedPersonId))
                .person()
                .copy();
        PersonCurrentStateProjector.Projection projection = stateProjector.project(person, now);

        String content = switch (command) {
            case "activity" -> formatActivities(person, now);
            case "activity24h" -> formatActivityHistory(person, now);
            case "state" -> formatState(person, projection, false);
            case "status" -> formatActivities(person, now)
                    + "\n\n"
                    + formatState(person, projection, true);
            case "effects" -> formatEffects(person, projection, now);
            default -> throw new IllegalStateException("unreachable command: " + command);
        };
        return Optional.of(new CommandResult(content, now));
    }

    private static Optional<String> parseCommand(String message) {
        if (!message.regionMatches(
                true,
                0,
                COMMAND_NAMESPACE,
                0,
                COMMAND_NAMESPACE.length()
        )) {
            return Optional.empty();
        }
        if (message.length() == COMMAND_NAMESPACE.length()) {
            return Optional.of("");
        }
        if (!Character.isWhitespace(message.charAt(COMMAND_NAMESPACE.length()))) {
            return Optional.empty();
        }
        return Optional.of(message
                .substring(COMMAND_NAMESPACE.length())
                .strip()
                .toLowerCase(Locale.ROOT));
    }

    private static boolean isKnownCommand(String command) {
        return "activity".equals(command)
                || "activity24h".equals(command)
                || "state".equals(command)
                || "status".equals(command)
                || "effects".equals(command);
    }

    private static String formatActivities(Person person, Instant now) {
        List<PersonEvent> events = person.getCurrentPersonEvents(now).stream()
                .sorted(Comparator
                        .comparing((PersonEvent event) -> event.getChannel().ordinal())
                        .thenComparing(PersonEvent::getStartTime))
                .toList();
        String displayName = person.getIdentity().displayName();
        if (events.isEmpty()) {
            return "当前活动（" + displayName + "）\n暂无正在进行的活动。";
        }

        ZoneId zone = person.getIdentity().timeZone();
        StringBuilder output = new StringBuilder("当前活动（")
                .append(displayName)
                .append("）");
        for (int index = 0; index < events.size(); index++) {
            PersonEvent event = events.get(index);
            output.append("\n\n")
                    .append(index + 1)
                    .append(". [")
                    .append(channelLabel(event.getChannel()))
                    .append("] ")
                    .append(event.getTitle())
                    .append("\n类型：")
                    .append(activityLabel(event.getActivityType()));
            if (!event.getLocation().isBlank()) {
                output.append("\n地点：").append(event.getLocation());
            }
            output.append("\n开始：")
                    .append(formatTime(event.getStartTime(), zone))
                    .append("\n持续：")
                    .append(formatDuration(Duration.between(event.getStartTime(), now)));
        }
        return output.toString();
    }

    private static String formatActivityHistory(Person person, Instant now) {
        Instant cutoff = now.minus(ACTIVITY_HISTORY_WINDOW);
        List<PersonEvent> events = person.getPersonTimeline().getAll().stream()
                .filter(event -> !event.getStartTime().isAfter(now))
                .filter(event -> event.getEndTime()
                        .map(end -> end.isAfter(cutoff))
                        .orElse(true))
                .sorted(Comparator.comparing(PersonEvent::getStartTime).reversed())
                .toList();
        String displayName = person.getIdentity().displayName();
        if (events.isEmpty()) {
            return "过去24小时活动（" + displayName + "）\n暂无活动记录。";
        }

        ZoneId zone = person.getIdentity().timeZone();
        StringBuilder output = new StringBuilder("过去24小时活动（")
                .append(displayName)
                .append("）");
        for (int index = 0; index < events.size(); index++) {
            PersonEvent event = events.get(index);
            Instant effectiveEnd = event.getEndTime().orElse(now);
            output.append("\n\n")
                    .append(index + 1)
                    .append(". [")
                    .append(channelLabel(event.getChannel()))
                    .append("] ")
                    .append(event.getTitle())
                    .append("\n类型：")
                    .append(activityLabel(event.getActivityType()));
            if (!event.getLocation().isBlank()) {
                output.append("\n地点：").append(event.getLocation());
            }
            output.append("\n开始：")
                    .append(formatTime(event.getStartTime(), zone))
                    .append("\n结束：")
                    .append(event.getEndTime()
                            .map(end -> formatTime(end, zone))
                            .orElse("进行中"))
                    .append("\n持续：")
                    .append(formatDuration(Duration.between(
                            event.getStartTime(),
                            effectiveEnd
                    )));
        }
        return output.toString();
    }

    private static String formatState(
            Person person,
            PersonCurrentStateProjector.Projection projection,
            boolean compact
    ) {
        PersonStateSnapshot state = projection.state();
        String heading = compact
                ? "主要状态"
                : "当前状态（" + person.getIdentity().displayName() + "）";
        StringBuilder output = new StringBuilder(heading)
                .append("\n情绪：")
                .append(signedPercent(state.valence()))
                .append("\n精力：")
                .append(percent(state.energy()))
                .append("\n专注：")
                .append(percent(state.focus()))
                .append("\n动力：")
                .append(percent(state.motivation()))
                .append("\n疲劳：")
                .append(percent(state.fatigue()));
        if (!compact) {
            output.append("\n紧张：")
                    .append(percent(state.tension()))
                    .append("\n心理负荷：")
                    .append(percent(state.mentalLoad()))
                    .append("\n困倦：")
                    .append(percent(state.sleepiness()))
                    .append("\n饥饿：")
                    .append(percent(state.hunger()))
                    .append("\n孤独：")
                    .append(percent(state.loneliness()))
                    .append("\n社交需求：")
                    .append(percent(state.socialNeed()));
        }
        projection.evolutionContext().previousUpdateTime().ifPresent(updatedAt -> output
                .append("\n最近结算：")
                .append(formatTime(updatedAt, person.getIdentity().timeZone())));
        return output.toString();
    }

    private static String formatEffects(
            Person person,
            PersonCurrentStateProjector.Projection projection,
            Instant now
    ) {
        Map<EventId, Instant> eventEndTimes = eventEndTimes(person);
        List<RegisteredStateEffect> effects = projection.evolutionContext().effects().values()
                .stream()
                .filter(effect -> effect.isActiveAt(now, eventEndTimes))
                .sorted(Comparator.comparing(RegisteredStateEffect::startsAt))
                .toList();
        if (effects.isEmpty()) {
            return "当前状态效果\n暂无生效中的状态效果。";
        }

        ZoneId zone = person.getIdentity().timeZone();
        StringBuilder output = new StringBuilder("当前状态效果");
        for (int index = 0; index < effects.size(); index++) {
            RegisteredStateEffect effect = effects.get(index);
            String transitions = String.join("、", effect.transitions().stream()
                    .map(transition -> dimensionLabel(transition.dimension())
                            + (transition.shape() > 0.0 ? "↑" : "↓"))
                    .toList());
            output.append("\n\n")
                    .append(index + 1)
                    .append(". ")
                    .append(effect.cause())
                    .append("\n类别：")
                    .append(effectTypeLabel(effect.type()))
                    .append("\n影响：")
                    .append(transitions)
                    .append("\n开始：")
                    .append(formatTime(effect.startsAt(), zone))
                    .append("\n结束：")
                    .append(effect.effectiveEndTime(eventEndTimes)
                            .map(end -> formatTime(end, zone))
                            .orElse("随活动结束"));
        }
        return output.toString();
    }

    private static Map<EventId, Instant> eventEndTimes(Person person) {
        Map<EventId, Instant> endTimes = new HashMap<>();
        for (PersonEvent event : person.getPersonTimeline().getAll()) {
            event.getEndTime().ifPresent(end -> endTimes.put(event.getId(), end));
        }
        return Map.copyOf(endTimes);
    }

    private static String formatTime(Instant instant, ZoneId zone) {
        return TIME_FORMATTER.withZone(zone).format(instant);
    }

    private static String formatDuration(Duration duration) {
        long minutes = Math.max(0L, duration.toMinutes());
        long hours = minutes / 60L;
        long remainingMinutes = minutes % 60L;
        if (hours == 0L) {
            return minutes + "分钟";
        }
        if (remainingMinutes == 0L) {
            return hours + "小时";
        }
        return hours + "小时" + remainingMinutes + "分钟";
    }

    private static String percent(double value) {
        return Math.round(value * 100.0) + "%";
    }

    private static String signedPercent(double value) {
        long percentage = Math.round(value * 100.0);
        return (percentage > 0L ? "+" : "") + percentage + "%";
    }

    private static String channelLabel(ActivityChannel channel) {
        return switch (channel) {
            case PRIMARY -> "主活动";
            case COMMUNICATION -> "沟通";
            case AUDIO -> "音频";
        };
    }

    private static String activityLabel(ActivityType type) {
        return switch (type) {
            case STUDY -> "学习";
            case WORK -> "工作";
            case EAT -> "吃饭";
            case SLEEP -> "睡眠";
            case REST -> "休息";
            case TRAVEL -> "出行";
            case EXERCISE -> "运动";
            case SOCIAL -> "社交";
            case ENTERTAINMENT -> "娱乐";
            case SHOPPING -> "购物";
            case CHAT -> "聊天";
            case LISTEN_MUSIC -> "听音乐";
            case OTHER -> "其他";
        };
    }

    private static String effectTypeLabel(StateEffectType type) {
        return switch (type) {
            case EMOTIONAL -> "情绪";
            case COGNITIVE -> "认知";
            case PHYSICAL -> "生理";
            case SOCIAL -> "社交";
            case GENERAL -> "综合";
        };
    }

    private static String dimensionLabel(StateDimension dimension) {
        return switch (dimension) {
            case VALENCE -> "情绪";
            case ENERGY -> "精力";
            case TENSION -> "紧张";
            case FOCUS -> "专注";
            case MENTAL_LOAD -> "心理负荷";
            case MOTIVATION -> "动力";
            case FATIGUE -> "疲劳";
            case SLEEPINESS -> "困倦";
            case HUNGER -> "饥饿";
            case LONELINESS -> "孤独";
            case SOCIAL_NEED -> "社交需求";
        };
    }
}
