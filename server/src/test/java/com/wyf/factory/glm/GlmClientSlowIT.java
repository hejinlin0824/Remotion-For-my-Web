package com.wyf.factory.glm;

import com.wyf.factory.config.AppProperties;
import com.wyf.factory.config.Secrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真调 GLM 的慢测试：只在有真实 key 时手动 `mvn -Pslow test -Dtest=GlmClientSlowIT` 跑
 * （Global Constraint 9：真调 API 打 slow 组，默认 mvn test 排除）。
 */
@Tag("slow")
class GlmClientSlowIT {

    @Test
    @DisplayName("真调 chat：system 只回复两个字：好的 → 回复含“好”")
    void chat_realCall_repliesGood() {
        boolean hasKey = System.getenv("ZHIPU_API_KEY") != null
                || System.getenv("ZHIPUAI_API_KEY") != null
                || System.getenv("GLM_API_KEY") != null;
        assumeTrue(hasKey, "无 GLM key（ZHIPU_API_KEY 等），跳过真调");

        GlmClient client = new GlmClient(
                new JdkHttpTransport(new AppProperties()), new Secrets(), new AppProperties());

        String reply = client.chat("只回复两个字：好的", "开始。");

        assertThat(reply).contains("好");
    }

    @Test
    @DisplayName("真调 chatWithImage：小图 → 返回非空文本")
    void chatWithImage_realCall_returnsText() {
        boolean hasKey = System.getenv("ZHIPU_API_KEY") != null
                || System.getenv("ZHIPUAI_API_KEY") != null
                || System.getenv("GLM_API_KEY") != null;
        assumeTrue(hasKey, "无 GLM key（ZHIPU_API_KEY 等），跳过真调");
        // 1x1 红色 PNG 的 base64
        String tinyPng = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";
        GlmClient client = new GlmClient(
                new JdkHttpTransport(new AppProperties()), new Secrets(), new AppProperties());

        String reply = client.chatWithImage("只回复两个字：好的", tinyPng, "image/png");

        assertThat(reply).isNotBlank();
    }
}
