package com.wyf.factory.stations;

import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.GlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EXTRACTING 工位单元测试：mock GlmClient（零 API 成本，Global Constraint 9）。
 * fixture 回放（text-case/image-case）= GLM 真实响应体，slow IT 手动真调捕获。
 */
class ExtractStationTest {

    /** 真调捕获 fixture 用的原题文本（与 ExtractStationSlowIT 同一题）。 */
    static final String TEXT_PAYLOAD = "已知函数 f(x)=x³+ax²+x，若 f(x) 在 R 上单调递增，求实数 a 的取值范围。";

    private final GlmClient glm = mock(GlmClient.class);
    private final ExtractStation station = new ExtractStation(glm);

    /** 读 fixture（GLM 真实响应体原文）。 */
    private static String fixture(String name) throws Exception {
        try (var in = ExtractStationTest.class.getClassLoader().getResourceAsStream("fixtures/extract/" + name)) {
            assertThat(in).as("fixture %s 存在", name).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("合法 JSON → ExtractResult 字段映射（problemType、lines[0].id=L1、segments 切分）")
    void validJson_mapsToExtractResult() {
        when(glm.chat(Prompts.EXTRACT, TEXT_PAYLOAD)).thenReturn("""
                {"problemType":"计算题","lines":[
                  {"id":"L1","segments":[
                    {"type":"text","value":"已知函数 "},
                    {"type":"math","value":"f(x)=x^{3}+ax^{2}+x"},
                    {"type":"text","value":"，"}]},
                  {"id":"L2","segments":[{"type":"text","value":"若 "}]}]}
                """);

        ExtractResult result = station.extract(TEXT_PAYLOAD);

        assertThat(result.problemType()).isEqualTo("计算题");
        assertThat(result.lines()).hasSize(2);
        assertThat(result.lines().get(0).id()).isEqualTo("L1");
        assertThat(result.lines().get(0).segments()).hasSize(3);
        assertThat(result.lines().get(0).segments().get(0).type()).isEqualTo("text");
        assertThat(result.lines().get(0).segments().get(1).type()).isEqualTo("math");
        assertThat(result.lines().get(0).segments().get(1).value()).isEqualTo("f(x)=x^{3}+ax^{2}+x");
        assertThat(result.lines().get(1).id()).isEqualTo("L2");
        // TEXT 路径：user 载荷 = 原题文本，system = Prompts.EXTRACT
        verify(glm).chat(Prompts.EXTRACT, TEXT_PAYLOAD);
    }

    @Test
    @DisplayName("```json 代码块包裹 → 先剥围栏再解析成功")
    void fencedJson_strippedAndParsed() {
        when(glm.chat(Prompts.EXTRACT, TEXT_PAYLOAD)).thenReturn("""
                ```json
                {"problemType":"基础题","lines":[{"id":"L1","segments":[{"type":"math","value":"1+1=2"}]}]}
                ```
                """);

        ExtractResult result = station.extract(TEXT_PAYLOAD);

        assertThat(result.problemType()).isEqualTo("基础题");
        assertThat(result.lines()).hasSize(1);
        assertThat(result.lines().get(0).segments().get(0).value()).isEqualTo("1+1=2");
    }

    @Test
    @DisplayName("extractImage → 走 chatWithImage（system=Prompts.EXTRACT、原样透传 base64/mime）")
    void extractImage_routesToChatWithImage() {
        when(glm.chatWithImage(Prompts.EXTRACT, "QUJD", "image/png")).thenReturn(
                "{\"problemType\":\"计算题\",\"lines\":[{\"id\":\"L1\",\"segments\":[{\"type\":\"text\",\"value\":\"求\"}]}]}");

        ExtractResult result = station.extractImage("QUJD", "image/png");

        assertThat(result.problemType()).isEqualTo("计算题");
        verify(glm).chatWithImage(Prompts.EXTRACT, "QUJD", "image/png");
        verify(glm, never()).chat(anyString(), anyString());
    }

    @Test
    @DisplayName("{\"error\":...} → FatalExtractException（原因进消息），不重试")
    void errorPayload_fatalExtractException() {
        when(glm.chat(Prompts.EXTRACT, TEXT_PAYLOAD)).thenReturn("{\"error\":\"图片不清晰\"}");

        assertThatThrownBy(() -> station.extract(TEXT_PAYLOAD))
                .isInstanceOf(ExtractStation.FatalExtractException.class)
                .hasMessageContaining("图片不清晰")
                .isNotInstanceOf(GlmException.class);
    }

    @Test
    @DisplayName("T27 废题判定：{\"notQuestion\":true,\"reason\":...} → FatalExtractException，消息=「上传/输入内容不是数学题目：<reason>」，不烧重试预算")
    void notQuestion_fatalExtractExceptionWithReason() {
        when(glm.chat(Prompts.EXTRACT, "帮我写一篇作文")).thenReturn(
                "{\"notQuestion\": true, \"reason\": \"输入是作文要求，不是数学题目\"}");

        assertThatThrownBy(() -> station.extract("帮我写一篇作文"))
                .isInstanceOf(ExtractStation.FatalExtractException.class)
                .hasMessageContaining("上传/输入内容不是数学题目")
                .hasMessageContaining("输入是作文要求，不是数学题目")
                .isNotInstanceOf(GlmException.class);
    }

    @Test
    @DisplayName("T27 废题判定（IMAGE 通道同规则）：视觉通道返回 notQuestion → 同样 FatalExtractException")
    void notQuestion_imageChannelSameRule() {
        when(glm.chatWithImage(Prompts.EXTRACT, "QUJD", "image/png")).thenReturn(
                "{\"notQuestion\": true, \"reason\": \"无关图片，未见数学题\"}");

        assertThatThrownBy(() -> station.extractImage("QUJD", "image/png"))
                .isInstanceOf(ExtractStation.FatalExtractException.class)
                .hasMessageContaining("不是数学题目")
                .hasMessageContaining("无关图片");
    }

    @Test
    @DisplayName("T27 废题判定：reason 缺失/空白 → 兜底「未说明原因」，仍是 Fatal 不重试")
    void notQuestion_missingReason_fallbackReason() {
        when(glm.chat(Prompts.EXTRACT, TEXT_PAYLOAD)).thenReturn("{\"notQuestion\": true}");

        assertThatThrownBy(() -> station.extract(TEXT_PAYLOAD))
                .isInstanceOf(ExtractStation.FatalExtractException.class)
                .hasMessageContaining("上传/输入内容不是数学题目")
                .hasMessageContaining("未说明原因");
    }

    @Test
    @DisplayName("T27 废题判定：notQuestion=false 或缺省 → 按既有形状正常解析（不误杀真题）")
    void notQuestionFalse_parsesNormally() {
        when(glm.chat(Prompts.EXTRACT, TEXT_PAYLOAD)).thenReturn(
                "{\"notQuestion\": false, \"problemType\":\"计算题\",\"lines\":[{\"id\":\"L1\",\"segments\":[{\"type\":\"text\",\"value\":\"求\"}]}]}");

        ExtractResult result = station.extract(TEXT_PAYLOAD);

        assertThat(result.problemType()).isEqualTo("计算题");
        assertThat(result.lines()).hasSize(1);
    }

    @Test
    @DisplayName("非 JSON 文本 → GlmException(retryable=true)，消息含原始响应")
    void nonJson_retryableGlmException() {
        when(glm.chat(Prompts.EXTRACT, TEXT_PAYLOAD)).thenReturn("这不是JSON");

        assertThatThrownBy(() -> station.extract(TEXT_PAYLOAD))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("这不是JSON")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("缺 lines 字段 → GlmException(retryable=true)")
    void missingLines_retryable() {
        when(glm.chat(Prompts.EXTRACT, TEXT_PAYLOAD)).thenReturn("{\"problemType\":\"计算题\"}");

        assertThatThrownBy(() -> station.extract(TEXT_PAYLOAD))
                .isInstanceOf(GlmException.class)
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("原始响应超 500 字符 → 异常消息只含截断 500 字符")
    void rawResponse_truncatedTo500InMessage() {
        String huge = "{\"problemType\":\"计算题\",\"note\":\"" + "长".repeat(600) + "\"}";
        when(glm.chat(Prompts.EXTRACT, TEXT_PAYLOAD)).thenReturn(huge);

        assertThatThrownBy(() -> station.extract(TEXT_PAYLOAD))
                .isInstanceOf(GlmException.class)
                .hasMessageNotContaining("长".repeat(501));
    }

    @Test
    @DisplayName("fixture 回放：文本题真实响应（GLM 真调捕获）→ 3 行全解析，含 math 段")
    void fixture_textCase_replays() throws Exception {
        when(glm.chat(Prompts.EXTRACT, TEXT_PAYLOAD)).thenReturn(fixture("text-case.json"));

        ExtractResult result = station.extract(TEXT_PAYLOAD);

        assertThat(result.problemType()).isEqualTo("计算题");
        assertThat(result.lines()).extracting(ExtractResult.Line::id).containsExactly("L1", "L2", "L3");
        assertThat(result.lines().get(0).segments())
                .anySatisfy(seg -> assertThat(seg.type()).isEqualTo("math"));
        assertThat(result.lines().get(1).segments())
                .anySatisfy(seg -> assertThat(seg.value()).contains("mathbb"));
    }

    @Test
    @DisplayName("fixture 回放：截图题真实响应（GLM 真调捕获，extractImage 路径）→ 5 行含 math 段")
    void fixture_imageCase_replays() throws Exception {
        when(glm.chatWithImage(Prompts.EXTRACT, "QUJD", "image/png")).thenReturn(fixture("image-case.json"));

        ExtractResult result = station.extractImage("QUJD", "image/png");

        assertThat(result.problemType()).isEqualTo("计算题");
        assertThat(result.lines()).hasSize(5);
        assertThat(result.lines().get(0).segments())
                .anySatisfy(seg -> assertThat(seg.type()).isEqualTo("math"));
        assertThat(result.lines().get(0).segments())
                .anySatisfy(seg -> assertThat(seg.value()).contains("X(z)"));
        // 选项行切成 text("A. ") + math 相邻段
        assertThat(result.lines().get(1).segments().get(0).value()).isEqualTo("A. ");
    }
}
