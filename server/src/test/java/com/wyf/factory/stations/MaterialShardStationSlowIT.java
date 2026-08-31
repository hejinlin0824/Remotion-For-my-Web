package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真调 GEN-P2 素材片的慢测试（T18 分片生成后继任原 MaterialStationSlowIT）：只在有真实 key 时
 * 手动 `mvn -Pslow test -Dtest=MaterialShardStationSlowIT` 跑（Global Constraint 9，默认 mvn test 排除）。
 *
 * <p>输入 = 同一示例题的审题 fixture（fixtures/extract/text-case.json）+ 骨架
 * （counts=golden 同构 3/5/2/3，锚点 L1/L2/L2/L3/L3）。断言：四段条数与骨架计划逐段一致、
 * usesAnchor 逐位等于指派（锚点只领不造）。</p>
 *
 * <p>端点说明（沿 ExtractStationSlowIT）：env `GLM_BASE_URL=https://open.bigmodel.cn/api/coding/paas/v4`
 * 覆盖生产默认 v4；key 只进进程环境，绝不打日志。</p>
 *
 * <p>fixture 捕获：`-Dfixture.capture=true` 把真调原始响应写入
 * fixtures/material/material-case.json（单元回放用；该 fixture 亦作 script-case 同源素材）。</p>
 */
@Tag("slow")
class MaterialShardStationSlowIT {

    private static final Path EXTRACT_FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "extract", "text-case.json");
    private static final Path MATERIAL_FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "material", "material-case.json");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("真调 P2 素材片 → 四段条数与骨架计划一致、usesAnchor 逐位等于指派")
    void generate_realCall_matchesSkeletonPlan() throws Exception {
        ExtractResult extract = extractFixture();

        Material material = station().generate(extract, skeleton());

        assertThat(material.knowledge()).hasSize(3);
        assertThat(material.steps()).hasSize(5);
        assertThat(material.pitfalls()).hasSize(2);
        assertThat(material.generalMethod()).hasSize(3);
        assertThat(material.steps()).extracting(Material.Step::usesAnchor)
                .containsExactly("L1", "L2", "L2", "L3", "L3");
    }

    @Test
    @EnabledIfSystemProperty(named = "fixture.capture", matches = "true")
    @DisplayName("捕获 fixture（-Dfixture.capture=true）：真调原始响应写入 fixtures/material/")
    void captureFixture() throws Exception {
        ExtractResult extract = extractFixture();
        GlmClient glm = new GlmClient(new JdkHttpTransport(props()), new Secrets(), props());
        MaterialShardStation station = new MaterialShardStation(glm);

        // 与生产同载荷形状（problemType/problem/plan/glossary），经 station 的 payload 组装：
        // 直接调 generate 再落盘原始响应不可得 → 以同一载荷真调 chat 一次并写入 fixture
        String raw = glm.chat(Prompts.MATERIAL, payloadOf(extract));
        writeFixture(raw);
        Material material = mapper.readValue(stripCodeFence(raw), Material.class);
        assertThat(material.steps().size()).isBetween(3, 10);
        assertThat(material.knowledge().size()).isBetween(2, 4);
    }

    private MaterialShardStation station() {
        AppProperties props = props();
        return new MaterialShardStation(new GlmClient(new JdkHttpTransport(props), new Secrets(), props));
    }

    /** 与 MaterialShardStation.payload 同形的用户载荷（fixture 捕获用）。 */
    private String payloadOf(ExtractResult extract) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("problemType", extract.problemType());
        root.set("problem", mapper.valueToTree(extract.lines()));
        ObjectNode plan = root.putObject("plan");
        ObjectNode counts = plan.putObject("counts");
        counts.put("knowledge", 3);
        counts.put("steps", 5);
        counts.put("pitfalls", 2);
        counts.put("generalMethod", 3);
        plan.set("anchors", mapper.valueToTree(List.of("L1", "L2", "L2", "L3", "L3")));
        root.set("glossary", mapper.valueToTree(List.of(mapper.createObjectNode()
                .put("term", "判别式").put("standard", "判别式（记号 Δ）"))));
        return mapper.writeValueAsString(root);
    }

    /** golden 同构骨架（counts 3/5/2/3，锚点 L1/L2/L2/L3/L3；场景计划真调本测试不消费，给最小合法值）。 */
    private static Skeleton skeleton() {
        return new Skeleton("计算题", new Skeleton.Counts(3, 5, 2, 3),
                List.of("L1", "L2", "L2", "L3", "L3"),
                List.of(new Skeleton.ScenePlan("s01", 2, "problem-card")),
                List.of(new Skeleton.GlossaryTerm("判别式", "判别式（记号 Δ）")));
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
