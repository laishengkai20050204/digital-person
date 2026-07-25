package com.laishengkai.digitalperson.infrastructure.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** Configuration for the self-hosted Mem0 REST adapter. */
@ConfigurationProperties(prefix = "digital-person.memory.mem0")
public record Mem0Properties(
        boolean enabled,
        boolean required,
        boolean retrievalEnabled,
        Double minimumRelevance,
        String extractionInstructions,
        URI baseUrl,
        String apiKey,
        Duration connectTimeout,
        Duration requestTimeout,
        String healthPath
) {
    static final double DEFAULT_MINIMUM_RELEVANCE = 0.30;
    static final String MANDATORY_EXTRACTION_GUARDRAILS = """
            长期记忆安全边界：
            1. 不要保存测试消息、占位内容、验证码、一次性代码、密码、API Key、访问令牌、密钥或其他认证信息。
            2. 用户明确要求不要记住或不要保存的当前内容，不得写入长期记忆。
            3. 不要保存无持续价值的寒暄、确认回复、瞬时情绪、临时状态或一次性调试内容；只有当它们构成重要经历、持续计划、稳定偏好或长期关系变化时才可保留。
            4. 不要把人物回复中的推测、安慰话术、角色扮演修辞或模型自行补充的内容当作用户事实。
            """.strip();
    private static final String DEFAULT_EXTRACTION_POLICY = """
            将提取出的长期记忆始终写成简体中文。只保存对未来交互有持续价值的明确事实、偏好、关系、目标、计划、承诺、习惯和重要经历；使用第三人称，表达简洁，一条记忆只包含一个主要事实；不要使用“Agent learned that”等英文模板，不要翻译人名、产品名等专有名词。
            """.strip();
    static final String DEFAULT_EXTRACTION_INSTRUCTIONS =
            MANDATORY_EXTRACTION_GUARDRAILS + "\n\n" + DEFAULT_EXTRACTION_POLICY;

    private static final URI DEFAULT_BASE_URL = URI.create("http://127.0.0.1:8888");
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String DEFAULT_HEALTH_PATH = "/auth/setup-status";

    public Mem0Properties {
        minimumRelevance = probability(minimumRelevance, "minimumRelevance");
        extractionInstructions = normalizeInstructions(extractionInstructions);
        baseUrl = validateBaseUrl(baseUrl == null ? DEFAULT_BASE_URL : baseUrl);
        apiKey = normalize(apiKey);
        connectTimeout = positive(
                connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout,
                "connectTimeout"
        );
        requestTimeout = positive(
                requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout,
                "requestTimeout"
        );
        healthPath = normalizePath(
                healthPath == null ? DEFAULT_HEALTH_PATH : healthPath
        );
        if (retrievalEnabled && !enabled) {
            throw new IllegalArgumentException(
                    "Mem0 retrieval cannot be enabled while Mem0 is disabled"
            );
        }
    }

    URI endpoint(String path) {
        String normalizedPath = normalizePath(path);
        String root = baseUrl.toString();
        while (root.endsWith("/")) {
            root = root.substring(0, root.length() - 1);
        }
        return URI.create(root + normalizedPath);
    }

    @Override
    public String toString() {
        return "Mem0Properties[enabled="
                + enabled
                + ", required="
                + required
                + ", retrievalEnabled="
                + retrievalEnabled
                + ", minimumRelevance="
                + minimumRelevance
                + ", extractionInstructions=<configured>, baseUrl="
                + baseUrl
                + ", apiKey=<redacted>, connectTimeout="
                + connectTimeout
                + ", requestTimeout="
                + requestTimeout
                + ", healthPath="
                + healthPath
                + "]";
    }

    private static URI validateBaseUrl(URI value) {
        URI uri = Objects.requireNonNull(value, "baseUrl cannot be null");
        String scheme = uri.getScheme();
        if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("baseUrl must use http or https");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("baseUrl must contain a host");
        }
        return uri;
    }

    private static Duration positive(Duration value, String fieldName) {
        Duration duration = Objects.requireNonNull(value, fieldName + " cannot be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
        return duration;
    }

    private static double probability(Double value, String fieldName) {
        double normalized = value == null ? DEFAULT_MINIMUM_RELEVANCE : value;
        if (!Double.isFinite(normalized) || normalized < 0.0 || normalized > 1.0) {
            throw new IllegalArgumentException(fieldName + " must be between 0.0 and 1.0");
        }
        return normalized;
    }

    private static String normalizeInstructions(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            return DEFAULT_EXTRACTION_INSTRUCTIONS;
        }
        if (normalized.startsWith(MANDATORY_EXTRACTION_GUARDRAILS)) {
            return normalized;
        }
        return MANDATORY_EXTRACTION_GUARDRAILS
                + "\n\n附加提取要求：\n"
                + normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalizePath(String value) {
        String normalized = normalize(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("path cannot be blank");
        }
        return normalized.startsWith("/") ? normalized : "/" + normalized;
    }
}
