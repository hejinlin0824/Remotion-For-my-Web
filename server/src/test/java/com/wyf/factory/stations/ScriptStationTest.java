package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.content.ContentJson;
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
 * ASSEMBLED 工位单元测试：mock GlmClient（零 API 成本）。
 * user 载荷 = {"problem":题干 JSON,"material":素材 JSON}；errors 重载追加失败清单。
 */
class ScriptStationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 MaterialStationTest 同一示例题的审题产物。 */
    private static final ExtractResult EXTRACT = MaterialStationTest.EXTRACT;

    /** 输入素材（MaterialStation 合法产物同构）。 */
    private static final Material MATERIAL = new Material(
            List.of(
                    new Material.Knowledge("可导函数单调递增等价于导数恒非负", "f'(x)\\ge 0", "f(x) 在区间内可导", "写成 f'(x)>0 会漏临界情形"),
                    new Material.Knowledge("二次不等式恒成立判别法", "\\Delta\\le 0", "先确认二次项系数符号", "忽略开口方向")),
            List.of(
                    new Material.Step("L1", "对 f(x) 求导", "f'(x)=3x^{2}+2ax+1", "三次函数导数是二次函数"),
                    new Material.Step("L2", "单调递增翻译成导数恒非负", "f'(x)\\ge 0", "关键转化"),
                    new Material.Step("L3", "解不等式写结论", "a\\in[-\\sqrt{3},\\sqrt{3}]", "端点是闭的")),
            List.of(new Material.Pitfall("把条件写成 f'(x)>0", "漏掉判别式等于零的临界情形")),
            List.of(
                    new Material.MethodItem("识别：可导函数+区间单调", "立刻联想导数恒定号"),
                    new Material.MethodItem("转化：单调⇔导数恒≥0", "含参二次上判别式"),
                    new Material.MethodItem("求解并回验", "取等情形代回验证")));

    /** 范围内最小合法剧本输出（problem 逐字复用题干）。 */
    private static final String VALID_JSON = """
            {"meta":{"aspect":"16:9","problemType":"计算题"},
             "problem":{"lines":[
              {"id":"L1","segments":[{"type":"text","value":"已知函数 "},{"type":"math","value":"f(x)=x^{3}+ax^{2}+x"},{"type":"text","value":"，"}]},
              {"id":"L2","segments":[{"type":"text","value":"若 "},{"type":"math","value":"f(x)"},{"type":"text","value":" 在 "},{"type":"math","value":"\\\\mathbb{R}"},{"type":"text","value":" 上单调递增，"}]},
              {"id":"L3","segments":[{"type":"text","value":"求实数 "},{"type":"math","value":"a"},{"type":"text","value":" 的取值范围。"}]}]},
             "knowledge":[
              {"claim":"可导函数单调递增等价于导数恒非负","formula":"f'(x)\\\\ge 0","premise":"f(x) 在区间内可导","trap":"写成 f'(x)>0 会漏临界情形"},
              {"claim":"二次不等式恒成立判别法","formula":"\\\\Delta\\\\le 0","premise":"先确认二次项系数符号","trap":"忽略开口方向"}],
             "steps":[
              {"usesAnchor":"L1","statement":"对 f(x) 求导","derivation":"f'(x)=3x^{2}+2ax+1","note":"三次函数导数是二次函数"},
              {"usesAnchor":"L2","statement":"单调递增翻译成导数恒非负","derivation":"f'(x)\\\\ge 0","note":"关键转化"},
              {"usesAnchor":"L3","statement":"解不等式写结论","derivation":"a\\\\in[-\\\\sqrt{3},\\\\sqrt{3}]","note":"端点是闭的"}],
             "pitfalls":[{"claim":"把条件写成 f'(x)>0","why":"漏掉判别式等于零的临界情形"}],
             "generalMethod":[
              {"step":"识别：可导函数+区间单调","trick":"立刻联想导数恒定号"},
              {"step":"转化：单调⇔导数恒≥0","trick":"含参二次上判别式"},
              {"step":"求解并回验","trick":"取等情形代回验证"}],
             "scenes":[
              {"id":"s01","act":2,"component":"problem-card","ttsText":"我们先看这道题。三行信息，一个三次函数和一个单调条件。最后要 a 的范围。","props":{}},
              {"id":"s02","act":2,"component":"knowledge-card","ttsText":"先回顾考点。可导函数单调递增，等价于导数恒大于等于零。注意不是严格大于。","props":{"knowledgeRef":1}},
              {"id":"s03","act":3,"component":"step-card","ttsText":"进入解法。第一步，对 f(x) 求导。导数是一个二次函数。","props":{"stepRef":1}},
              {"id":"s04","act":3,"component":"step-card","ttsText":"第二步，最关键的翻译。单调递增就是导数恒非负。","props":{"stepRef":2}},
              {"id":"s05","act":3,"component":"step-card","ttsText":"第三步，解不等式下结论。a 属于闭区间。端点取得到。","props":{"stepRef":3}},
              {"id":"s06","act":4,"component":"general-list","ttsText":"以后怎么做？先识别题型。马上联想导数恒定号。","props":{"itemRef":1}}]}
            """;

    private final GlmClient glm = mock(GlmClient.class);
    private final ScriptStation station = new ScriptStation(glm);

    @Test
    @DisplayName("合法输出 → ContentJson 映射（meta/problem 逐字复用/scenes props）")
    void validJson_mapsToContentJson() {
        when(glm.chat(eq(Prompts.SCRIPT), anyString())).thenReturn(VALID_JSON);

        ContentJson script = station.assemble(EXTRACT, MATERIAL);

        assertThat(script.meta().aspect()).isEqualTo("16:9");
        assertThat(script.meta().problemType()).isEqualTo("计算题");
        assertThat(problemOf(script)).isEqualTo(EXTRACT);
        assertThat(script.knowledge()).hasSize(2);
        assertThat(script.steps()).hasSize(3);
        assertThat(script.pitfalls()).hasSize(1);
        assertThat(script.generalMethod()).hasSize(3);
        assertThat(script.scenes()).hasSize(6);
        assertThat(script.scenes().get(0).component()).isEqualTo("problem-card");
        assertThat(script.scenes().get(0).props()).isEmpty();
        assertThat(script.scenes().get(1).props()).isEqualTo(java.util.Map.of("knowledgeRef", 1));
        assertThat(script.scenes().get(3).component()).isEqualTo("step-card");
        assertThat(script.scenes().get(3).props()).isEqualTo(java.util.Map.of("stepRef", 2));
        assertThat(script.scenes().get(5).component()).isEqualTo("general-list");
        assertThat(script.scenes().get(5).act()).isEqualTo(4);
        assertThat(script.scenes().get(5).props()).isEqualTo(java.util.Map.of("itemRef", 1));
    }

    @Test
    @DisplayName("user 载荷 = {\"problem\":题干,\"material\":素材} 紧凑 JSON")
    void userPayload_isProblemPlusMaterial() throws Exception {
        when(glm.chat(eq(Prompts.SCRIPT), anyString())).thenReturn(VALID_JSON);

        station.assemble(EXTRACT, MATERIAL);

        ObjectNode expected = MAPPER.createObjectNode();
        expected.set("problem", MAPPER.valueToTree(EXTRACT));
        expected.set("material", MAPPER.valueToTree(MATERIAL));
        verify(glm).chat(Prompts.SCRIPT, MAPPER.writeValueAsString(expected));
    }

    @Test
    @DisplayName("scenes 条目缺 ttsText → retryable 且消息含 scenes[2] 缺必需字段 ttsText")
    void sceneMissingField_listedInMessage() {
        String broken = VALID_JSON.replace(
                "\"id\":\"s05\",\"act\":3,\"component\":\"step-card\",\"ttsText\":\"第三步，解不等式下结论。a 属于闭区间。端点取得到。\",\"props\":{\"stepRef\":3}",
                "\"id\":\"s05\",\"act\":3,\"component\":\"step-card\",\"props\":{\"stepRef\":3}");
        when(glm.chat(eq(Prompts.SCRIPT), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.assemble(EXTRACT, MATERIAL))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("scenes[4] 缺必需字段 ttsText")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("meta 缺 aspect → retryable 且消息含 meta 缺必需字段 aspect")
    void metaMissingAspect_listedInMessage() {
        String broken = VALID_JSON.replace("\"meta\":{\"aspect\":\"16:9\",\"problemType\":\"计算题\"}",
                "\"meta\":{\"problemType\":\"计算题\"}");
        when(glm.chat(eq(Prompts.SCRIPT), anyString())).thenReturn(broken);

        assertThatThrownBy(() -> station.assemble(EXTRACT, MATERIAL))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("meta 缺必需字段 aspect")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("pitfalls 条数 4 超出范围 1-3 → retryable 且消息含具体条目")
    void countOutOfRange_retryableWithSpecificMessage() {
        String fourPitfalls = VALID_JSON.replace(
                "\"pitfalls\":[{\"claim\":\"把条件写成 f'(x)>0\",\"why\":\"漏掉判别式等于零的临界情形\"}],",
                "\"pitfalls\":[{\"claim\":\"c1\",\"why\":\"w\"},{\"claim\":\"c2\",\"why\":\"w\"},{\"claim\":\"c3\",\"why\":\"w\"},{\"claim\":\"c4\",\"why\":\"w\"}],");
        when(glm.chat(eq(Prompts.SCRIPT), anyString())).thenReturn(fourPitfalls);

        assertThatThrownBy(() -> station.assemble(EXTRACT, MATERIAL))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("pitfalls 条数 4 超出范围 1-3")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("非 JSON 输出 → retryable")
    void nonJson_retryable() {
        when(glm.chat(eq(Prompts.SCRIPT), anyString())).thenReturn("剧本不是JSON");

        assertThatThrownBy(() -> station.assemble(EXTRACT, MATERIAL))
                .isInstanceOf(GlmException.class)
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("errors 重载：user 载荷尾部追加失败清单（逐条 - 前缀）")
    void errorsOverload_appendsChecklistToPayload() throws Exception {
        when(glm.chat(eq(Prompts.SCRIPT), anyString())).thenReturn(VALID_JSON);

        station.assemble(EXTRACT, MATERIAL,
                List.of("scenes[4] 缺必需字段 ttsText", "pitfalls 条数 4 超出范围 1-3"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(glm).chat(eq(Prompts.SCRIPT), payload.capture());
        ObjectNode expected = MAPPER.createObjectNode();
        expected.set("problem", MAPPER.valueToTree(EXTRACT));
        expected.set("material", MAPPER.valueToTree(MATERIAL));
        assertThat(payload.getValue())
                .startsWith(MAPPER.writeValueAsString(expected))
                .contains("上一轮校验失败清单（必须全部修正）：")
                .contains("\n- scenes[4] 缺必需字段 ttsText")
                .contains("\n- pitfalls 条数 4 超出范围 1-3");
    }

    @Test
    @DisplayName("fixture 回放：真实响应（GLM 真调捕获）→ 绑定 ContentJson，scenes 结构合规")
    void fixture_replays() throws Exception {
        String raw = fixture("script/script-case.json");
        when(glm.chat(eq(Prompts.SCRIPT), anyString())).thenReturn(raw);

        ContentJson script = station.assemble(EXTRACT, MATERIAL);

        assertThat(script.meta().problemType()).isEqualTo("计算题");
        assertThat(problemOf(script)).isEqualTo(EXTRACT);
        assertThat(script.scenes()).isNotEmpty();
        assertThat(script.scenes().get(0).component()).isEqualTo("problem-card");
        assertThat(script.scenes()).allSatisfy(scene -> {
            assertThat(scene.id()).isNotBlank();
            assertThat(scene.ttsText()).isNotBlank();
            assertThat(scene.props()).isNotNull();
        });
    }

    /** ContentJson 的 meta.problemType + problem.lines 折算成 ExtractResult（断言「problem 逐字复用题干」）。 */
    private static ExtractResult problemOf(ContentJson script) {
        return new ExtractResult(script.meta().problemType(), script.problem().lines().stream()
                .map(line -> new ExtractResult.Line(line.id(), line.segments().stream()
                        .map(seg -> new ExtractResult.Seg(seg.type(), seg.value())).toList()))
                .toList());
    }

    private static String fixture(String name) throws Exception {
        try (var in = ScriptStationTest.class.getClassLoader().getResourceAsStream("fixtures/" + name)) {
            assertThat(in).as("fixture %s 存在", name).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
