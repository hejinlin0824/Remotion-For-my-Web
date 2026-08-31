package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.GlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GEN-P2 素材片工位单元测试（T18）：mock GlmClient（零 API 成本）。
 * 骨架绑定校验 = 四段条数与骨架计划逐段一致 + steps[i].usesAnchor 与骨架指派逐位一致
 * （锚点只领不造）+ 条目必需字段；fixture 回放 = 真调捕获响应（锚点/条数按 fixture 骨架）。
 */
public class MaterialShardStationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 ExtractStationSlowIT 同一示例题的审题产物（fixture 回放共用；pipeline 测试亦复用）。 */
    public static final ExtractResult EXTRACT = new ExtractResult("计算题", List.of(
            new ExtractResult.Line("L1", List.of(
                    new ExtractResult.Seg("text", "已知函数 "),
                    new ExtractResult.Seg("math", "f(x)=x^{3}+ax^{2}+x"),
                    new ExtractResult.Seg("text", "，"))),
            new ExtractResult.Line("L2", List.of(
                    new ExtractResult.Seg("text", "若 "),
                    new ExtractResult.Seg("math", "f(x)"),
                    new ExtractResult.Seg("text", " 在 "),
                    new ExtractResult.Seg("math", "\\mathbb{R}"),
                    new ExtractResult.Seg("text", " 上单调递增，"))),
            new ExtractResult.Line("L3", List.of(
                    new ExtractResult.Seg("text", "求实数 "),
                    new ExtractResult.Seg("math", "a"),
                    new ExtractResult.Seg("text", " 的取值范围。")))));

    /** 范围内最小合法骨架（与 VALID_JSON 同计划；stepRef 计划级分派 1..steps 自洽，T18.1）。 */
    static final Skeleton SKELETON = new Skeleton("计算题",
            new Skeleton.Counts(2, 3, 1, 3),
            List.of("L1", "L2", "L3"),
            List.of(new Skeleton.ScenePlan("s01", 2, "problem-card", null),
                    new Skeleton.ScenePlan("s02", 2, "knowledge-card", null),
                    new Skeleton.ScenePlan("s03", 3, "step-card", 1),
                    new Skeleton.ScenePlan("s04", 3, "step-card", 2),
                    new Skeleton.ScenePlan("s05", 3, "step-card", 3),
                    new Skeleton.ScenePlan("s06", 4, "general-list", null)),
            List.of(new Skeleton.GlossaryTerm("判别式", "判别式（记号 Δ）")));

    /** 与骨架计划完全一致的素材输出。 */
    private static final String VALID_JSON = """
            {"knowledge":[
              {"claim":"可导函数单调递增等价于导数恒非负","formula":"f'(x)\\\\ge 0","premise":"f(x) 在区间内可导","trap":"写成 f'(x)>0 会漏临界情形"},
              {"claim":"二次不等式恒成立判别法","formula":"\\\\Delta\\\\le 0","premise":"先确认二次项系数符号","trap":"忽略开口方向"}],
             "steps":[
              {"usesAnchor":"L1","statement":"对 f(x) 求导","derivation":"f'(x)=3x^{2}+2ax+1","note":"三次函数导数是二次函数"},
              {"usesAnchor":"L2","statement":"单调递增翻译成导数恒非负","derivation":"f'(x)\\\\ge 0","note":"关键转化"},
              {"usesAnchor":"L3","statement":"解不等式写结论","derivation":"a\\\\in[-\\\\sqrt{3},\\\\sqrt{3}]","note":"端点是闭的"}],
             "pitfalls":[
              {"claim":"把条件写成 f'(x)>0","why":"漏掉判别式等于零的临界情形"}],
             "generalMethod":[
              {"step":"识别：可导函数+区间单调","trick":"立刻联想导数恒定号"},
              {"step":"转化：单调⇔导数恒≥0","trick":"含参二次上判别式"},
              {"step":"求解并回验","trick":"取等情形代回验证"}]}
            """;

    private final GlmClient glm = mock(GlmClient.class);
    private final MaterialShardStation station = new MaterialShardStation(glm);

    @Test
    @DisplayName("合法输出 → Material 四段映射（字段值路径）")
    void validJson_mapsToMaterial() {
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(VALID_JSON);

        Material material = station.generate(EXTRACT, SKELETON);

        assertThat(material.knowledge()).hasSize(2);
        assertThat(material.knowledge().get(0).claim()).contains("单调递增");
        assertThat(material.knowledge().get(1).formula()).isEqualTo("\\Delta\\le 0");
        assertThat(material.steps()).hasSize(3);
        assertThat(material.steps().get(0).usesAnchor()).isEqualTo("L1");
        assertThat(material.steps().get(2).derivation()).isEqualTo("a\\in[-\\sqrt{3},\\sqrt{3}]");
        assertThat(material.pitfalls()).hasSize(1);
        assertThat(material.pitfalls().get(0).why()).contains("临界情形");
        assertThat(material.generalMethod()).hasSize(3);
    }

    @Test
    @DisplayName("user 载荷 = {problemType, problem, plan:{counts,anchors}, glossary}；errors 追加清单")
    void payload_carriesPlanAndGlossary() throws Exception {
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(VALID_JSON);

        station.generate(EXTRACT, SKELETON, List.of("素材 knowledge 条数 3 与骨架计划 2 不一致（条数以骨架为准）"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(glm).chat(eq(Prompts.MATERIAL), payload.capture());
        ObjectNode parsed = (ObjectNode) MAPPER.readTree(payload.getValue());
        assertThat(parsed.path("problemType").asText()).isEqualTo("计算题");
        assertThat(parsed.path("problem").isArray()).isTrue();
        assertThat(parsed.path("plan").path("counts").path("knowledge").asInt()).isEqualTo(2);
        assertThat(parsed.path("plan").path("counts").path("steps").asInt()).isEqualTo(3);
        assertThat(parsed.path("plan").path("anchors").get(2).asText()).isEqualTo("L3");
        assertThat(parsed.path("glossary").get(0).path("term").asText()).isEqualTo("判别式");
        assertThat(payload.getValue())
                .contains("上一轮校验失败清单（必须全部修正）：")
                .contains("\n- 素材 knowledge 条数 3 与骨架计划 2 不一致（条数以骨架为准）");
    }

    @Test
    @DisplayName("骨架绑定：knowledge 条数与计划不一致 → retryable 且消息含计划值")
    void countMismatchWithPlan_retryable() {
        String three = VALID_JSON.replace(
                "{\"claim\":\"二次不等式恒成立判别法\",\"formula\":\"\\\\Delta\\\\le 0\",\"premise\":\"先确认二次项系数符号\",\"trap\":\"忽略开口方向\"}",
                "{\"claim\":\"k2\",\"formula\":\"f\",\"premise\":\"p\",\"trap\":\"t\"},{\"claim\":\"k3\",\"formula\":\"f\",\"premise\":\"p\",\"trap\":\"t\"}");
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(three);

        assertThatThrownBy(() -> station.generate(EXTRACT, SKELETON))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("素材 knowledge 条数 3 与骨架计划 2 不一致（条数以骨架为准）")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("锚点只领不造：steps[1].usesAnchor 偏离骨架指派 → retryable 且消息含指派值")
    void anchorDeviatesFromAssignment_retryable() {
        String deviated = VALID_JSON.replace(
                "{\"usesAnchor\":\"L2\",\"statement\":\"单调递增翻译成导数恒非负\"",
                "{\"usesAnchor\":\"L1\",\"statement\":\"单调递增翻译成导数恒非负\"");
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(deviated);

        assertThatThrownBy(() -> station.generate(EXTRACT, SKELETON))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("素材 steps[1].usesAnchor='L1' 与骨架指派 'L2' 不一致")
                .hasMessageContaining("锚点只能领用骨架指派，不得自行改锚");
    }

    @Test
    @DisplayName("缺必需字段（knowledge[1].trap 缺失）→ retryable 且消息逐条列出")
    void missingField_listedInMessage() {
        String missing = VALID_JSON.replace(
                ",\"trap\":\"忽略开口方向\"}", "}");
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(missing);

        assertThatThrownBy(() -> station.generate(EXTRACT, SKELETON))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("knowledge[1] 缺必需字段 trap");
    }

    @Test
    @DisplayName("非 JSON 输出 → retryable")
    void nonJson_retryable() {
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn("我不是JSON");

        assertThatThrownBy(() -> station.generate(EXTRACT, SKELETON))
                .isInstanceOf(GlmException.class)
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("fixture 回放：真实响应（GLM 真调捕获）→ 四段按 fixture 骨架全解析，锚点逐位一致")
    void fixture_replays() throws Exception {
        String raw = fixture("material/material-case.json");
        Skeleton plan = new Skeleton("计算题",
                new Skeleton.Counts(4, 6, 3, 4),
                List.of("L1", "L2", "L2", "L3", "L3", "L3"),
                List.of(new Skeleton.ScenePlan("s01", 2, "problem-card", null)),
                List.of(new Skeleton.GlossaryTerm("判别式", "判别式")));
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(raw);

        Material material = station.generate(EXTRACT, plan);

        assertThat(material.knowledge()).hasSize(4);
        assertThat(material.steps()).hasSize(6);
        assertThat(material.steps()).extracting(Material.Step::usesAnchor)
                .containsExactly("L1", "L2", "L2", "L3", "L3", "L3");
        assertThat(material.pitfalls()).hasSize(3);
        assertThat(material.generalMethod()).hasSize(4);
    }

    private static String fixture(String name) throws Exception {
        try (var in = MaterialShardStationTest.class.getClassLoader().getResourceAsStream("fixtures/" + name)) {
            assertThat(in).as("fixture %s 存在", name).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
