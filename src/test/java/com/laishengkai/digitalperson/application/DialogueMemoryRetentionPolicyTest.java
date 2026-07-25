package com.laishengkai.digitalperson.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DialogueMemoryRetentionPolicyTest {
    private final DialogueMemoryRetentionPolicy policy = new DialogueMemoryRetentionPolicy();

    @Test
    void suppressesSyntheticOptOutAndCredentialPayloads() {
        List<String> suppressed = List.of(
                "测试",
                "这是一条测试消息。",
                "ＴＥＳＴ",
                "这句话不要保存",
                "请不要把当前消息写入长期记忆",
                "Don't remember this message",
                "验证码：493821",
                "临时密码 abcdef",
                "API key: abc12345",
                "我的访问令牌是 token-123456",
                "sk-proj-abcdefghijklmnop"
        );

        assertThat(suppressed)
                .allSatisfy(message -> assertThat(policy.shouldRecord(message))
                        .as("message should be suppressed: %s", message)
                        .isFalse());
    }

    @Test
    void keepsDurableStatementsThatOnlyMentionSimilarTerms() {
        List<String> retained = List.of(
                "我下周参加软件测试工程师面试",
                "医生让我下周做过敏原检测",
                "我正在学习大模型 token 的计费方式",
                "我的目标是以后从事 API 安全测试",
                "我平时使用密码管理器保存账号",
                "我希望你记住我最喜欢科幻电影"
        );

        assertThat(retained)
                .allSatisfy(message -> assertThat(policy.shouldRecord(message))
                        .as("message should remain eligible: %s", message)
                        .isTrue());
    }
}
