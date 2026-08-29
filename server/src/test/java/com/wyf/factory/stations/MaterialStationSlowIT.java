package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.config.Secrets;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.JdkHttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真调 MATERIALIZED 工位的慢测试：只在有真实 key 时手动
 * `mvn -Pslow test -Dtest=MaterialStationSlowIT` 跑（Global Constraint 9，默认 mvn test 排除）。
 *
 * <p>输入 = 同一示例题的审题 fixture（fixtures/extract/text-case.json，T5 真调产物）。</p>
 *
 * <p>端点说明（沿 ExtractStationSlowIT）：生产默认 v4（app.glm.base-url）。本机 coding-plan key
 * （settings.json ANTHROPIC_AUTH_TOKEN 先例）在 v4 端点无渠道，但 OpenAI 兼容的 coding 端点
 * 可用——设 env `GLM_BASE_URL=https://open.bigmodel.cn/api/coding/paas/v4` 覆盖；未设该 env 时
 * 保持生产默认 v4。key 只进进程环境，绝不打日志。</p>
 *
 * <p>fixture 捕获：`mvn -Pslow test -Dtest=MaterialStationSlowIT -Dfixture.capture=true`
 * 时把真调原始响应写入 fixtures/material/material-case.json（单元测试回放用，默认不写）。</p>
 */
@Tag("slow")
class MaterialStationSlowIT {

    private static final Path EXTRACT_FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "extract", "text-case.json");
    private static final Path MATERIAL_FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "material", "material-case.json");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("真调 generate → 四段条数在范围内、usesAnchor 都指向题干行 id")
    void generate_realCall_fourSegmentsInRange() throws Exception {
        ExtractResult extract = extractFixture();

        Material material = station().generate(extract);

        assertThat(material.knowledge().size()).isBetween(2, 4);
        assertThat(material.steps().size()).isBetween(3, 10);
        assertThat(material.pitfalls().size()).isBetween(1, 3);
        assertThat(material.generalMethod().size()).isBetween(3, 6);
        assertThat(material.steps()).allSatisfy(step ->
                assertThat(step.usesAnchor()).isIn(extract.lines().stream().map(ExtractResult.Line::id).toList()));
    }

    @Test
    @EnabledIfSystemProperty(named = "fixture.capture", matches = "true")
    @DisplayName("捕获 fixture（-Dfixture.capture=true）：真调原始响应写入 fixtures/material/")
    void captureFixture() throws Exception {
        ExtractResult extract = extractFixture();
        GlmClient glm = new GlmClient(new JdkHttpTransport(props()), new Secrets(), props());

        String raw = glm.chat(Prompts.MATERIAL, mapper.writeValueAsString(extract));
        writeFixture(raw);
        Material material = mapper.readValue(stripCodeFence(raw), Material.class);
        assertThat(material.knowledge().size()).isBetween(2, 4);
        assertThat(material.steps().size()).isBetween(3, 10);
    }

    private MaterialStation station() {
        AppProperties props = props();
        return new MaterialStation(new GlmClient(new JdkHttpTransport(props), new Secrets(), props));
    }

    private ExtractResult extractFixture() throws Exception {
        assertThat(Files.isRegularFile(EXTRACT_FIXTURE)).as("审题 fixture 存在").isTrue();
        return mapper.readValue(Files.readString(EXTRACT_FIXTURE, StandardCharsets.UTF_8), ExtractResult.class);
    }

    private static void writeFixture(String raw) throws Exception {
        Files.createDirectories(MATERIAL_FIXTURE.getParent());
        Files.writeString(MATERIAL_FIXTURE, raw, StandardCharsets.UTF_8);
    }

    private static String stripCodeFence(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            s = firstNewline >= 0 ? s.substring(firstNewline + 1) : "";
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.strip();
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
