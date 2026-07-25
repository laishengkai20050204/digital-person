package com.laishengkai.digitalperson.application;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rejects clearly synthetic, explicitly non-persistent, or credential-bearing dialogue
 * before it reaches a long-term memory provider.
 *
 * <p>The policy is deliberately conservative: ordinary mentions of software testing,
 * medical tests, passwords, or API tokens remain eligible unless the message is itself
 * a synthetic test payload, contains an assigned secret, or explicitly opts the current
 * content out of long-term memory.</p>
 */
public final class DialogueMemoryRetentionPolicy {
    private static final int PATTERN_FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Set<String> SYNTHETIC_MESSAGES = Set.of(
            "测试",
            "测试一下",
            "测试消息",
            "测试内容",
            "仅测试",
            "这是测试",
            "这是一条测试消息",
            "test",
            "test message",
            "test only",
            "testing",
            "placeholder",
            "占位",
            "占位消息",
            "占位内容",
            "ping",
            "hello world",
            "1",
            "11",
            "111",
            "123",
            "1234",
            "123456",
            "abc",
            "asdf"
    );

    private static final String CURRENT_ITEM =
            "(?:这条(?:消息)?|这句话|这段(?:话|内容)?|当前(?:消息|内容)|"
                    + "本次(?:消息|对话|测试)|此次(?:消息|对话|测试)|"
                    + "刚才(?:这句|这段|的内容)?|下面(?:这段|的内容)?)";

    private static final Pattern CURRENT_ITEM_OPT_OUT = Pattern.compile(
            CURRENT_ITEM
                    + ".{0,16}(?:不要|别|无需|不用).{0,8}(?:记住|保存|记录|存储)"
                    + "|(?:不要|别|无需|不用).{0,8}(?:记住|保存|记录|存储).{0,16}"
                    + CURRENT_ITEM,
            PATTERN_FLAGS
    );

    private static final Pattern EXPLICIT_MEMORY_OPT_OUT = Pattern.compile(
            "(?:请)?(?:不要|别|无需|不用).{0,10}"
                    + "(?:记住|记到|写入|加入|存入|保存到|记录到).{0,8}(?:长期)?记忆"
                    + "|(?:请)?(?:不要|别|无需|不用).{0,8}"
                    + "(?:长期|永久).{0,6}(?:保存|记录|存储)",
            PATTERN_FLAGS
    );

    private static final Pattern ENGLISH_CURRENT_ITEM_OPT_OUT = Pattern.compile(
            "\\b(?:do\\s+not|don't|dont|never)\\s+"
                    + "(?:remember|save|store|memorize)\\s+"
                    + "(?:this|the\\s+current|that)\\s+"
                    + "(?:message|text|content|conversation)\\b"
                    + "|\\b(?:this|the\\s+current|that)\\s+"
                    + "(?:message|text|content|conversation)\\s+"
                    + "(?:should\\s+not|must\\s+not|is\\s+not\\s+to\\s+be)\\s+"
                    + "(?:remembered|saved|stored|memorized)\\b",
            PATTERN_FLAGS
    );

    private static final Pattern WELL_KNOWN_CREDENTIAL = Pattern.compile(
            "(?:\\bsk-[a-z0-9_-]{12,}\\b"
                    + "|\\bgh[pousr]_[a-z0-9]{20,}\\b"
                    + "|\\bgithub_pat_[a-z0-9_]{20,}\\b"
                    + "|\\bxox[baprs]-[a-z0-9-]{10,}\\b"
                    + "|\\beyj[a-z0-9_-]{8,}\\.[a-z0-9_-]{8,}\\.[a-z0-9_-]{8,}\\b)",
            PATTERN_FLAGS
    );

    private static final Pattern ONE_TIME_CODE_ASSIGNMENT = Pattern.compile(
            "(?:验证码|动态码|短信码|测试码|一次性代码|一次性密码|临时密码|"
                    + "otp|2fa(?:\\s*code)?|verification\\s*code|test\\s*code|"
                    + "temporary\\s*password)"
                    + "\\s*(?:是|为|[:：=]|is)?\\s*[\\\"'“”]?([a-z0-9]{4,12})",
            PATTERN_FLAGS
    );

    private static final Pattern CHINESE_CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?:授权令牌|访问令牌|api\\s*密钥|密钥|密码)"
                    + "\\s*(?:是|为|[:：=])?\\s*[\\\"'“”]?([a-z0-9_./+=-]{4,256})",
            PATTERN_FLAGS
    );

    private static final Pattern ENGLISH_CREDENTIAL_ASSIGNMENT = Pattern.compile(
            "(?:api\\s*[-_]?\\s*key|access\\s+token|refresh\\s+token|"
                    + "bearer\\s+token|password)"
                    + "\\s*(?:is|[:=])\\s*[\\\"']?([a-z0-9_./+=-]{4,256})",
            PATTERN_FLAGS
    );

    public boolean shouldRecord(String userMessage) {
        String normalized = normalize(userMessage);
        if (SYNTHETIC_MESSAGES.contains(normalized)) {
            return false;
        }
        if (CURRENT_ITEM_OPT_OUT.matcher(normalized).find()
                || EXPLICIT_MEMORY_OPT_OUT.matcher(normalized).find()
                || ENGLISH_CURRENT_ITEM_OPT_OUT.matcher(normalized).find()) {
            return false;
        }
        return !WELL_KNOWN_CREDENTIAL.matcher(normalized).find()
                && !ONE_TIME_CODE_ASSIGNMENT.matcher(normalized).find()
                && !CHINESE_CREDENTIAL_ASSIGNMENT.matcher(normalized).find()
                && !ENGLISH_CREDENTIAL_ASSIGNMENT.matcher(normalized).find();
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(
                Objects.requireNonNull(value, "userMessage cannot be null"),
                Normalizer.Form.NFKC
        ).strip().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("\\s+", " ");
        return normalized.replaceAll("[。.!！?？]+$", "").strip();
    }
}
