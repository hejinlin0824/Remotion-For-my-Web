package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * MATERIALIZED 工位单元测试：mock GlmClient（零 API 成本）。
 * 校验失败清单 = 回传重试的错误清单（消息逐条列出具体差异）。
 */
class MaterialStationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 与 ExtractStationSlowIT 同一示例题的审题产物（fixture 回放共用）。 */
    static final ExtractResult EXTRACT = new ExtractResult("计算题", List.of(
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

    /** 范围内最小合法素材输出。 */
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
    private final MaterialStation station = new MaterialStation(glm);

    @Test
    @DisplayName("合法输出 → Material 四段映射（字段值路径）")
    void validJson_mapsToMaterial() {
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(VALID_JSON);

        Material material = station.generate(EXTRACT);

        assertThat(material.knowledge()).hasSize(2);
        assertThat(material.knowledge().get(0).claim()).contains("单调递增");
        assertThat(material.knowledge().get(1).formula()).isEqualTo("\\Delta\\le 0");
        assertThat(material.steps()).hasSize(3);
        assertThat(material.steps().get(0).usesAnchor()).isEqualTo("L1");
        assertThat(material.steps().get(2).derivation()).isEqualTo("a\\in[-\\sqrt{3},\\sqrt{3}]");
        assertThat(material.pitfalls()).hasSize(1);
        assertThat(material.pitfalls().get(0).why()).contains("临界情形");
        assertThat(material.generalMethod()).hasSize(3);
        assertThat(material.generalMethod().get(1).step()).startsWith("转化");
    }

    @Test
    @DisplayName("user 载荷 = 题干 JSON（ExtractResult 序列化），system = Prompts.MATERIAL")
    void userPayload_isExtractJson() throws Exception {
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(VALID_JSON);

        station.generate(EXTRACT);

        verify(glm).chat(Prompts.MATERIAL, MAPPER.writeValueAsString(EXTRACT));
    }

    @Test
    @DisplayName("```json 围栏包裹 → 先剥围栏再解析成功")
    void fencedJson_strippedAndParsed() {
        when(glm.chat(eq(Prompts.MATERIAL), anyString()))
                .thenReturn("```json\n" + VALID_JSON + "\n```");

        Material material = station.generate(EXTRACT);

        assertThat(material.knowledge()).hasSize(2);
    }

    @Test
    @DisplayName("knowledge 条数 5 超出范围 2-4 → retryable 且消息含具体条目")
    void countOutOfRange_retryableWithSpecificMessage() {
        String five = """
                {"knowledge":[
                  {"claim":"k1","formula":"f","premise":"p","trap":"t"},
                  {"claim":"k2","formula":"f","premise":"p","trap":"t"},
                  {"claim":"k3","formula":"f","premise":"p","trap":"t"},
                  {"claim":"k4","formula":"f","premise":"p","trap":"t"},
                  {"claim":"k5","formula":"f","premise":"p","trap":"t"}],
                 "steps":[
                  {"usesAnchor":"L1","statement":"s","derivation":"d","note":"n"},
                  {"usesAnchor":"L2","statement":"s","derivation":"d","note":"n"},
                  {"usesAnchor":"L3","statement":"s","derivation":"d","note":"n"}],
                 "pitfalls":[{"claim":"c","why":"w"}],
                 "generalMethod":[{"step":"s","trick":"t"},{"step":"s","trick":"t"},{"step":"s","trick":"t"}]}
                """;
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(five);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("knowledge 条数 5 超出范围 2-4")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("缺必需字段（knowledge[0].trap 缺失）→ retryable 且消息逐条列出")
    void missingField_listedInMessage() {
        String missing = """
                {"knowledge":[
                  {"claim":"k1","formula":"f","premise":"p"},
                  {"claim":"k2","formula":"f","premise":"p","trap":"t"}],
                 "steps":[
                  {"usesAnchor":"L1","statement":"s","derivation":"d","note":"n"},
                  {"usesAnchor":"L2","statement":"s","derivation":"d","note":"n"},
                  {"usesAnchor":"L3","statement":"s","derivation":"d","note":"n"}],
                 "pitfalls":[{"claim":"c","why":"w"}],
                 "generalMethod":[{"step":"s","trick":"t"},{"step":"s","trick":"t"},{"step":"s","trick":"t"}]}
                """;
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(missing);

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("knowledge[0] 缺必需字段 trap")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("非 JSON 输出 → retryable")
    void nonJson_retryable() {
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn("我不是JSON");

        assertThatThrownBy(() -> station.generate(EXTRACT))
                .isInstanceOf(GlmException.class)
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("fixture 回放：真实响应（GLM 真调捕获）→ Material 四段全解析，usesAnchor 指向题干")
    void fixture_replays() throws Exception {
        String raw = fixture("material/material-case.json");
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(raw);

        Material material = station.generate(EXTRACT);

        assertThat(material.knowledge().size()).isBetween(2, 4);
        assertThat(material.steps().size()).isBetween(3, 10);
        assertThat(material.pitfalls().size()).isBetween(1, 3);
        assertThat(material.generalMethod().size()).isBetween(3, 6);
        assertThat(material.steps()).allSatisfy(step ->
                assertThat(step.usesAnchor()).isIn(EXTRACT.lines().stream().map(ExtractResult.Line::id).toList()));
        assertThat(material.knowledge()).allSatisfy(k -> {
            assertThat(k.claim()).isNotBlank();
            assertThat(k.formula()).isNotBlank();
        });
    }

    private static String fixture(String name) throws Exception {
        try (var in = MaterialStationTest.class.getClassLoader().getResourceAsStream("fixtures/" + name)) {
            assertThat(in).as("fixture %s 存在", name).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("errors 重载：user 载荷尾部追加失败清单（逐条 - 前缀）")
    void errorsOverload_appendsChecklistToPayload() throws Exception {
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(VALID_JSON);

        station.generate(EXTRACT, List.of("knowledge 条数 5 超出范围 2-4", "knowledge[0] 缺必需字段 trap"));

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(glm).chat(eq(Prompts.MATERIAL), payload.capture());
        assertThat(payload.getValue())
                .startsWith(MAPPER.writeValueAsString(EXTRACT))
                .contains("上一轮校验失败清单（必须全部修正）：")
                .contains("\n- knowledge 条数 5 超出范围 2-4")
                .contains("\n- knowledge[0] 缺必需字段 trap");
    }

    @Test
    @DisplayName("无错误时重载与单参行为一致：载荷不带清单段")
    void emptyErrors_noChecklistInPayload() throws Exception {
        when(glm.chat(eq(Prompts.MATERIAL), anyString())).thenReturn(VALID_JSON);

        station.generate(EXTRACT, List.of());

        verify(glm).chat(Prompts.MATERIAL, MAPPER.writeValueAsString(EXTRACT));
    }
}
