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
 * 真调 GEN-P2 素材片的慢测试（T18 分片生成后继任原 MaterialStationSlowIT，T30 随一拆二重写：
 * P2a 核心=steps 独占 / P2b 周边=三段）：只在有真实 key 时手动
 * `mvn -Pslow test -Dtest=MaterialShardStationSlowIT` 跑（Global Constraint 9，默认 mvn test 排除）。
 *
 * <p>输入 = 同一示例题的审题 fixture（fixtures/extract/text-case.json）+ 骨架
 * （counts=golden 同构 3/5/2/3，锚点 L1/L2/L2/L3/L3）。断言：P2a steps 条数与骨架计划一致、
 * usesAnchor 逐位等于指派（锚点只领不造）；P2b 三段条数与骨架计划一致，且载荷携带 P2a 成品。</p>
 *
 * <p>端点说明（沿 ExtractStationSlowIT）：env `GLM_BASE_URL=https://open.bigmodel.cn/api/coding/paas/v4`
 * 覆盖生产默认 v4；key 只进进程环境，绝不打日志。</p>
 *
 * <p>fixture 捕获：`-Dfixture.capture=true` 把真调原始响应写入
 * fixtures/material/core-case.json（steps 段）与 fixtures/material/rest-case.json（三段）
 * （单元回放用；现 fixture 均自真调捕获 material-case.json 确定性切片派生，T30）。</p>
 */
@Tag("slow")
class MaterialShardStationSlowIT {

    private static final Path EXTRACT_FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "extract", "text-case.json");
    private static final Path CORE_FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "material", "core-case.json");
    private static final Path REST_FIXTURE =
            Path.of("src", "test", "resources", "fixtures", "material", "rest-case.json");

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("真调 P2a 核心片 → steps 条数与骨架计划一致、usesAnchor 逐位等于指派")
    void coreRealCall_matchesSkeletonPlan() throws Exception {
        ExtractResult extract = extractFixture();

        List<Material.Step> steps = station().generateCore(extract, skeleton());

        assertThat(steps).hasSize(5);
        assertThat(steps).extracting(Material.Step::usesAnchor)
                .containsExactly("L1", "L2", "L2", "L3", "L3");
    }

    @Test
    @DisplayName("真调 P2b 周边片（载荷带 P2a 成品）→ 三段条数与骨架计划一致")
    void restRealCall_matchesSkeletonPlan() throws Exception {
        ExtractResult extract = extractFixture();
        MaterialShardStation station = station();
        List<Material.Step> steps = station.generateCore(extract, skeleton());

        MaterialShardStation.Rest rest = station.generateRest(extract, skeleton(), steps);

        assertThat(rest.knowledge()).hasSize(3);
        assertThat(rest.pitfalls()).hasSize(2);
        assertThat(rest.generalMethod()).hasSize(3);
    }

    @Test
    @EnabledIfSystemProperty(named = "fixture.capture", matches = "true")
    @DisplayName("捕获 fixture（-Dfixture.capture=true）：P2a/P2b 真调原始响应写入 fixtures/material/")
    void captureFixture() throws Exception {
        ExtractResult extract = extractFixture();
        GlmClient glm = new GlmClient(new JdkHttpTransport(props()), new Secrets(), props());
        MaterialShardStation station = new MaterialShardStation(glm);

        // 与生产同载荷形状（problemType/problem/plan/glossary），经 station 的 payload 组装：
        // 直接调 generate 再落盘原始响应不可得 → 以同一载荷真调 chat 一次并写入 fixture
        String core = glm.chat(Prompts.MATERIAL_CORE, corePayloadOf(extract));
        writeFixture(CORE_FIXTURE, core);
        List<Material.Step> steps = station.generateCore(extract, skeleton());
        assertThat(steps.size()).isBetween(3, 10);

        String rest = glm.chat(Prompts.MATERIAL_REST, restPayloadOf(extract, steps));
        writeFixture(REST_FIXTURE, rest);
        MaterialShardStation.Rest restObj = mapper.readValue(stripCodeFence(rest), MaterialShardStation.Rest.class);
        assertThat(restObj.knowledge().size()).isBetween(2, 4);
        assertThat(restObj.pitfalls().size()).isBetween(1, 3);
        assertThat(restObj.generalMethod().size()).isBetween(3, 6);
    }

    private MaterialShardStation station() {
        AppProperties props = props();
        return new MaterialShardStation(new GlmClient(new JdkHttpTransport(props), new Secrets(), props));
    }

    /** 与 MaterialShardStation P2a 载荷同形（fixture 捕获用）。 */
    private String corePayloadOf(ExtractResult extract) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("problemType", extract.problemType());
        root.set("problem", mapper.valueToTree(extract.lines()));
        ObjectNode plan = root.putObject("plan");
        plan.putObject("counts").put("steps", 5);
        plan.set("anchors", mapper.valueToTree(List.of("L1", "L2", "L2", "L3", "L3")));
        root.set("glossary", mapper.valueToTree(List.of(glossary())));
        return mapper.writeValueAsString(root);
    }

    /** 与 MaterialShardStation P2b 载荷同形（P2a 同款基础载荷 + steps 成品）。 */
    private String restPayloadOf(ExtractResult extract, List<Material.Step> steps) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("problemType", extract.problemType());
        root.set("problem", mapper.valueToTree(extract.lines()));
        ObjectNode plan = root.putObject("plan");
        ObjectNode counts = plan.putObject("counts");
        counts.put("knowledge", 3);
        counts.put("pitfalls", 2);
        counts.put("generalMethod", 3);
        plan.set("anchors", mapper.valueToTree(List.of("L1", "L2", "L2", "L3", "L3")));
        root.set("steps", mapper.valueToTree(steps));
        root.set("glossary", mapper.valueToTree(List.of(glossary())));
        return mapper.writeValueAsString(root);
    }

    private static ObjectNode glossary() {
        return new ObjectMapper().createObjectNode().put("term", "判别式").put("standard", "判别式（记号 Δ）");
    }

    /** golden 同构骨架（counts 3/5/2/3，锚点 L1/L2/L2/L3/L3；场景计划真调本测试不消费，给最小合法值）。 */
    private static Skeleton skeleton() {
        return new Skeleton("计算题", new Skeleton.Counts(3, 5, 2, 3),
                List.of("L1", "L2", "L2", "L3", "L3"),
                List.of(new Skeleton.ScenePlan("s01", 2, "problem-card", null)),
                List.of(new Skeleton.GlossaryTerm("判别式", "判别式（记号 Δ）")));
    }

    private ExtractResult extractFixture() throws Exception {
        assertThat(Files.isRegularFile(EXTRACT_FIXTURE)).as("审题 fixture 存在").isTrue();
        return mapper.readValue(Files.readString(EXTRACT_FIXTURE, StandardCharsets.UTF_8), ExtractResult.class);
    }

    private static void writeFixture(Path file, String raw) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, raw, StandardCharsets.UTF_8);
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
