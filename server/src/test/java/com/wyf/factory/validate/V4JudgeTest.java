package com.wyf.factory.validate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.GlmException;
import com.wyf.factory.stations.ExtractResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V4 语义审校测试：mock GlmClient（零 API 成本）三路输出（PASS / REJECT+理由 / 乱格式）
 * + 内容 >400 字符 soft 预警（不阻断 pass）。golden 作输入 fixture。
 */
class V4JudgeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path goldenFile;

    private final GlmClient glm = mock(GlmClient.class);
    private final V4Judge validator = new V4Judge(glm);

    @BeforeAll
    static void locateGolden() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("template/src/data/content.json"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("仓库根（含 template/src/data/content.json）").isNotNull();
        goldenFile = dir.resolve("template/src/data/content.json");
    }

    @Test
    @DisplayName("PASS → pass；user 载荷 = content.json 完整 JSON，system = 语义审查提示词")
    void passPath() throws Exception {
        ContentJson golden = loadGolden();
        when(glm.chat(eq(V4Judge.SYSTEM_PROMPT), anyString())).thenReturn("PASS");

        var result = validator.validate(ctx(golden));

        assertThat(result.pass()).isTrue();
        assertThat(result.errors()).isEmpty();
        verify(glm).chat(V4Judge.SYSTEM_PROMPT, golden.toJson());
    }

    @Test
    @DisplayName("T28 科目中性化：V4 终审词「考研阅卷专家/考研讲题视频」，判据「推理/计算正确」替代「数学正确」；五项审查结构不变")
    void systemPromptIsSubjectNeutral() {
        assertThat(V4Judge.SYSTEM_PROMPT)
                .contains("你是考研阅卷专家，负责终审一份考研讲题视频的 content.json")
                .contains("解题步骤推理/计算正确、推导无跳跃")
                .as("中性化钉子：不再出现「考研数学」限定")
                .doesNotContain("考研数学")
                .as("五项审查与输出格式锚点原样（PASS/REJECT 协议零变化）")
                .contains("请逐项审查以下五点，任何一点不成立即 REJECT")
                .contains("首行必须且只能是 PASS 或 REJECT");
    }

    @Test
    @DisplayName("REJECT + 理由行 → fail，理由逐条进 errors")
    void rejectPathWithReasons() throws Exception {
        when(glm.chat(eq(V4Judge.SYSTEM_PROMPT), anyString()))
                .thenReturn("REJECT\n- 步骤 2 存在推导跳跃\n- usesAnchor 条件对应错误");

        var result = validator.validate(ctx(loadGolden()));

        assertThat(result.pass()).isFalse();
        assertThat(result.errors()).containsExactly("- 步骤 2 存在推导跳跃", "- usesAnchor 条件对应错误");
    }

    @Test
    @DisplayName("乱格式（首行非 PASS/REJECT）→ GlmException(retryable=true)")
    void junkFormatRetryable() throws Exception {
        when(glm.chat(eq(V4Judge.SYSTEM_PROMPT), anyString())).thenReturn("这道题整体没问题，可以过。");

        assertThatThrownBy(() -> validator.validate(ctx(loadGolden())))
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("未守格式")
                .extracting("retryable")
                .isEqualTo(true);
    }

    @Test
    @DisplayName("PASS 首行带尾随内容仍按 PASS 处理")
    void passWithTrailingLines() throws Exception {
        when(glm.chat(eq(V4Judge.SYSTEM_PROMPT), anyString())).thenReturn("PASS\n（五项均已复核）");

        var result = validator.validate(ctx(loadGolden()));

        assertThat(result.pass()).isTrue();
    }

    @Test
    @DisplayName("formula/derivation >400 字符 → soft 预警（不阻断 pass，不进 errors）")
    void longContentSoftWarning() throws Exception {
        ContentJson content = mutate(root -> {
            props(root, 5).put("formula", "f".repeat(401));
            ((ObjectNode) root.get("steps").get(0)).put("derivation", "d".repeat(401));
            ((ObjectNode) root.get("steps").get(0)).put("note", "n".repeat(401));
        });
        when(glm.chat(eq(V4Judge.SYSTEM_PROMPT), anyString())).thenReturn("PASS");

        var result = validator.validate(ctx(content));

        assertThat(result.pass()).as("soft 预警不阻断").isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.softErrors()).hasSize(3);
        assertThat(result.softErrors()).anyMatch(e -> e.startsWith("V4:") && e.contains("s06") && e.contains("formula"));
        assertThat(result.softErrors()).anyMatch(e -> e.startsWith("V4:") && e.contains("s05") && e.contains("derivation"));
        assertThat(result.softErrors()).anyMatch(e -> e.startsWith("V4:") && e.contains("s05") && e.contains("note"));
    }

    @Test
    @DisplayName("恰 400 字符不预警")
    void atThresholdNoWarning() throws Exception {
        ContentJson content = mutate(root -> props(root, 5).put("formula", "f".repeat(400)));
        when(glm.chat(eq(V4Judge.SYSTEM_PROMPT), anyString())).thenReturn("PASS");

        var result = validator.validate(ctx(content));

        assertThat(result.softErrors()).isEmpty();
    }

    // ---- helpers ----

    private ContentJson loadGolden() throws Exception {
        return MAPPER.readValue(goldenFile.toFile(), ContentJson.class);
    }

    private static ContentJson mutate(Consumer<ObjectNode> edit) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(goldenFile.toFile());
        edit.accept(root);
        return MAPPER.treeToValue(root, ContentJson.class);
    }

    private static ValidationContext ctx(ContentJson content) {
        ExtractResult extracted = new ExtractResult(content.meta().problemType(), content.problem().lines().stream()
                .map(line -> new ExtractResult.Line(line.id(), line.segments().stream()
                        .map(seg -> new ExtractResult.Seg(seg.type(), seg.value())).toList()))
                .toList());
        return new ValidationContext(content, extracted);
    }

    private static ObjectNode props(ObjectNode root, int index) {
        return (ObjectNode) root.get("scenes").get(index).get("props");
    }
}
