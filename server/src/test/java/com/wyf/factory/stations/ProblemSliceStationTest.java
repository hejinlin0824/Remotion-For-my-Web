package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * GEN-P1 题干片工位单元测试（T18）：mock GlmClient。
 * 保真校验（V2 口径镜像）：行数/id/段数/段类型全等，math 去空白相等、text 归一化相等；
 * 违反抛 retryable GlmException（工位级先拦，防烧 V2 驳回轮）。
 */
class ProblemSliceStationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ExtractResult EXTRACT = MaterialShardStationTest.EXTRACT;

    /** 与输入逐段一致的回显输出（保真红线下的合法输出）。 */
    private static final String ECHO_JSON = """
            {"lines":[
              {"id":"L1","segments":[{"type":"text","value":"已知函数 "},{"type":"math","value":"f(x)=x^{3}+ax^{2}+x"},{"type":"text","value":"，"}]},
              {"id":"L2","segments":[{"type":"text","value":"若 "},{"type":"math","value":"f(x)"},{"type":"text","value":" 在 "},{"type":"math","value":"\\\\mathbb{R}"},{"type":"text","value":" 上单调递增，"}]},
              {"id":"L3","segments":[{"type":"text","value":"求实数 "},{"type":"math","value":"a"},{"type":"text","value":" 的取值范围。"}]}]}
            """;

    private final GlmClient glm = mock(GlmClient.class);
    private final ProblemSliceStation station = new ProblemSliceStation(glm);

    @Test
    @DisplayName("回显输出 → problem.lines 绑定（行 id/段序列一致）")
    void echo_mapsToLines() {
        when(glm.chat(eq(Prompts.PROBLEM_SLICE), anyString())).thenReturn(ECHO_JSON);

        List<ContentJson.Line> lines = station.format(EXTRACT);

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).id()).isEqualTo("L1");
        assertThat(lines.get(1).segments()).hasSize(5);
        assertThat(lines.get(1).segments().get(3).value()).isEqualTo("\\mathbb{R}");
        assertThat(lines.get(2).segments().get(1).type()).isEqualTo("math");
    }

    @Test
    @DisplayName("排版微调放行：math 段空格差异、text 段全角/空格差异（V2 归一化口径）")
    void typographyOnlyDifferences_pass() {
        String tweaked = ECHO_JSON
                .replace("f(x)=x^{3}+ax^{2}+x", "f(x)=x^{3} + ax^{2} + x")
                .replace("已知函数 ", "已知函数  ");
        when(glm.chat(eq(Prompts.PROBLEM_SLICE), anyString())).thenReturn(tweaked);

        List<ContentJson.Line> lines = station.format(EXTRACT);

        assertThat(lines).hasSize(3);
        assertThat(lines.get(0).segments().get(1).value()).isEqualTo("f(x)=x^{3} + ax^{2} + x");
    }

    @Test
    @DisplayName("保真红线：math 段内容被改写 → retryable 且消息含行段定位")
    void mathRewritten_retryable() {
        String rewritten = ECHO_JSON.replace("f(x)=x^{3}+ax^{2}+x", "f(x)=x^{3}+2ax^{2}+x");
        when(glm.chat(eq(Prompts.PROBLEM_SLICE), anyString())).thenReturn(rewritten);

        assertThatThrownBy(() -> station.format(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("题干片 L1 段 2 内容被改写（保真红线）")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("保真红线：行 id 被改 → retryable")
    void lineIdChanged_retryable() {
        String changed = ECHO_JSON.replace("\"id\":\"L2\"", "\"id\":\"L4\"");
        when(glm.chat(eq(Prompts.PROBLEM_SLICE), anyString())).thenReturn(changed);

        assertThatThrownBy(() -> station.format(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("题干片 第 2 行 id 不一致");
    }

    @Test
    @DisplayName("保真红线：段数增删 → retryable")
    void segmentCountChanged_retryable() {
        String dropped = ECHO_JSON.replace(
                "{\"type\":\"text\",\"value\":\"，\"}", "{\"type\":\"text\",\"value\":\"，\"},{\"type\":\"text\",\"value\":\"！\"}");
        when(glm.chat(eq(Prompts.PROBLEM_SLICE), anyString())).thenReturn(dropped);

        assertThatThrownBy(() -> station.format(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("题干片 L1 段数不一致：输出 4 段 vs 输入 3 段");
    }

    @Test
    @DisplayName("非 JSON → retryable；user 载荷 = {problemType, lines} + errors 清单")
    void nonJson_andPayload() {
        when(glm.chat(eq(Prompts.PROBLEM_SLICE), anyString())).thenReturn("not-json").thenReturn(ECHO_JSON);

        assertThatThrownBy(() -> station.format(EXTRACT))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("题干片输出不是 JSON");

        station.format(EXTRACT, List.of("V2: L1 段 2 不一致"));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(glm, org.mockito.Mockito.times(2)).chat(eq(Prompts.PROBLEM_SLICE), payload.capture());
        assertThat(payload.getAllValues().get(1))
                .startsWith(MAPPER.createObjectNode()
                        .put("problemType", "计算题")
                        .set("lines", MAPPER.valueToTree(EXTRACT.lines())).toString())
                .contains("上一轮校验失败清单（必须全部修正）：")
                .contains("\n- V2: L1 段 2 不一致");
    }
}
