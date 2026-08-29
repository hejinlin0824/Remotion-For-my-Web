package com.wyf.factory.stations;

import com.wyf.factory.config.AppProperties;
import com.wyf.factory.config.Secrets;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.JdkHttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真调 EXTRACTING 工位的慢测试：只在有真实 key 时手动
 * `mvn -Pslow test -Dtest=ExtractStationSlowIT` 跑（Global Constraint 9，默认 mvn test 排除）。
 *
 * <p>端点说明：生产默认 v4（app.glm.base-url）。本机 coding-plan key（settings.json
 * ANTHROPIC_AUTH_TOKEN 先例）在 v4 端点无渠道（HTTP 429 code 1113），但 OpenAI 兼容的
 * coding 端点可用——设 env `GLM_BASE_URL=https://open.bigmodel.cn/api/coding/paas/v4` 覆盖；
 * 未设该 env 时保持生产默认 v4。key 只进进程环境，绝不打日志。</p>
 */
@Tag("slow")
class ExtractStationSlowIT {

    @Test
    @DisplayName("真调 extract（文本题）→ lines 非空且含 math 段")
    void extract_realCall_linesNonEmptyWithMath() {
        boolean hasKey = System.getenv("ZHIPU_API_KEY") != null
                || System.getenv("ZHIPUAI_API_KEY") != null
                || System.getenv("GLM_API_KEY") != null;
        assumeTrue(hasKey, "无 GLM key（ZHIPU_API_KEY 等），跳过真调");

        ExtractStation station = new ExtractStation(new GlmClient(
                new JdkHttpTransport(props()), new Secrets(), props()));

        ExtractResult result = station.extract(ExtractStationTest.TEXT_PAYLOAD);

        assertThat(result.problemType()).isIn(List.of("基础题", "计算题", "证明题", "应用题"));
        assertThat(result.lines()).isNotEmpty();
        assertThat(result.lines()).anySatisfy(line ->
                assertThat(line.segments()).anyMatch(seg -> "math".equals(seg.type())));
    }

    /** 生产默认配置；env GLM_BASE_URL 可覆盖端点（见类注释）。 */
    private static AppProperties props() {
        AppProperties props = new AppProperties();
        String override = System.getenv("GLM_BASE_URL");
        if (override != null && !override.isBlank()) {
            props.getGlm().setBaseUrl(override.strip());
        }
        return props;
    }
}
