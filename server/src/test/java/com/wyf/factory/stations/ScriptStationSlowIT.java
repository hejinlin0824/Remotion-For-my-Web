package com.wyf.factory.stations;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.config.Secrets;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.JdkHttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真调 ASSEMBLED 工位的慢测试：只在有真实 key 时手动
 * `mvn -Pslow test -Dtest=ScriptStationSlowIT` 跑（Global Constraint 9，默认 mvn test 排除）。
 *
 * <p>输入 = 同一示例题的审题 fixture + MaterialStation 真调素材（同一示例题链路）。</p>
 *
 * <p>端点说明（沿 ExtractStationSlowIT）：env `GLM_BASE_URL=https://open.bigmodel.cn/api/coding/paas/v4`
 * 覆盖生产默认 v4（本机 coding-plan key 只在 coding 端点有渠道）；key 只进进程环境，绝不打日志。</p>
 *
 * <p>fixture 捕获：`mvn -Pslow test -Dtest=ScriptStationSlowIT -Dfixture.capture=true`
 * 时把真调原始响应写入 fixtures/script/script-case.json（单元测试回放用，默认不写）。</p>
 */
@Tag("slow")
class ScriptStationSlowIT {

    private static final Path EXTRACT_FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "extract", "text-case.json");
    private static final Path SCRIPT_FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "script", "script-case.json");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("真调 assemble → meta 正确、problem 逐字复用、scenes 首场 problem-card 且 ref 不越界")
    void assemble_realCall_contentJsonStructure() throws Exception {
        ExtractResult extract = extractFixture();
        AppProperties props = props();
        Material material = new MaterialStation(
                new GlmClient(new JdkHttpTransport(props), new Secrets(), props)).generate(extract);
        ContentJson script = new ScriptStation(
                new GlmClient(new JdkHttpTransport(props), new Secrets(), props)).assemble(extract, material);

        assertThat(script.meta().aspect()).isEqualTo("16:9");
        assertThat(script.meta().problemType()).isEqualTo(extract.problemType());
        assertThat(problemOf(script)).isEqualTo(extract);

        List<ContentJson.Scene> scenes = script.scenes();
        assertThat(scenes).isNotEmpty();
        assertThat(scenes.get(0).component()).isEqualTo("problem-card");
        assertThat(scenes).allSatisfy(scene -> {
            assertThat(scene.act()).isIn(2, 3, 4);
            assertThat(scene.ttsText()).isNotBlank();
        });
        // act 升序分块：act2 全部 → act3 全部 → act4 全部
        List<Integer> acts = scenes.stream().map(ContentJson.Scene::act).toList();
        assertThat(acts).isSorted();
        // ref 不越界（轻查；重活归 V1/T7）
        assertThat(scenes).allSatisfy(scene -> {
            assertThat(ref(scene.props(), "knowledgeRef")).isBetween(1, script.knowledge().size());
            assertThat(ref(scene.props(), "stepRef")).isBetween(1, script.steps().size());
            assertThat(ref(scene.props(), "pitfallRef")).isBetween(1, script.pitfalls().size());
            assertThat(ref(scene.props(), "itemRef")).isBetween(1, script.generalMethod().size());
            if (scene.props().get("pitfallRefs") instanceof List<?> refs) {
                assertThat(refs).allSatisfy(r -> assertThat(((Number) r).intValue()).isBetween(1, script.pitfalls().size()));
            }
        });
    }

    @Test
    @EnabledIfSystemProperty(named = "fixture.capture", matches = "true")
    @DisplayName("捕获 fixture（-Dfixture.capture=true）：真调原始响应写入 fixtures/script/")
    void captureFixture() throws Exception {
        ExtractResult extract = extractFixture();
        AppProperties props = props();
        Material material = new MaterialStation(
                new GlmClient(new JdkHttpTransport(props), new Secrets(), props)).generate(extract);

        ObjectNode payload = mapper.createObjectNode();
        payload.set("problem", mapper.valueToTree(extract));
        payload.set("material", mapper.valueToTree(material));
        String raw = new GlmClient(new JdkHttpTransport(props), new Secrets(), props)
                .chat(Prompts.SCRIPT, mapper.writeValueAsString(payload));

        Files.createDirectories(SCRIPT_FIXTURE.getParent());
        Files.writeString(SCRIPT_FIXTURE, raw, StandardCharsets.UTF_8);
        ContentJson script = mapper.readValue(stripCodeFence(raw), ContentJson.class);
        assertThat(script.scenes()).isNotEmpty();
        assertThat(script.scenes().get(0).component()).isEqualTo("problem-card");
    }

    private static ExtractResult problemOf(ContentJson script) {
        return new ExtractResult(script.meta().problemType(), script.problem().lines().stream()
                .map(line -> new ExtractResult.Line(line.id(), line.segments().stream()
                        .map(seg -> new ExtractResult.Seg(seg.type(), seg.value())).toList()))
                .toList());
    }

    /** props 里指定 ref 的整数值；不存在返回 1（下界内，越界判定交给别处）。 */
    private static int ref(java.util.Map<String, Object> props, String name) {
        return props.get(name) instanceof Number number ? number.intValue() : 1;
    }

    private ExtractResult extractFixture() throws Exception {
        assertThat(Files.isRegularFile(EXTRACT_FIXTURE)).as("审题 fixture 存在").isTrue();
        return mapper.readValue(Files.readString(EXTRACT_FIXTURE, StandardCharsets.UTF_8), ExtractResult.class);
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
