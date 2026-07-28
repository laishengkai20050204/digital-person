package com.laishengkai.digitalperson.dialogue;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DialogueResultTest {

    @Test
    void stripsLeadingInternalHistoryTimestampFromUserFacingReply() {
        DialogueResult result = new DialogueResult(
                "",
                List.of("[2026-07-28 10:23:58 +08:00 Asia/Shanghai] 收到，测试通过。")
        );

        assertThat(result.replies()).containsExactly("收到，测试通过。");
    }

    @Test
    void stripsUtcHistoryTimestampAndRepeatedPrefixes() {
        DialogueResult result = new DialogueResult(
                "",
                List.of(
                        "[2026-07-25 00:59:00 Z] [2026-07-28 10:23:58 +08:00 Asia/Shanghai] 好呀，我在。"
                )
        );

        assertThat(result.replies()).containsExactly("好呀，我在。");
    }

    @Test
    void preservesOrdinaryBracketedReplyText() {
        DialogueResult result = new DialogueResult(
                "",
                List.of("[认真] 这件事我记得。", "正文里的 [2026-07-28 10:23:58 +08:00 Asia/Shanghai] 不删除")
        );

        assertThat(result.replies()).containsExactly(
                "[认真] 这件事我记得。",
                "正文里的 [2026-07-28 10:23:58 +08:00 Asia/Shanghai] 不删除"
        );
    }
}
