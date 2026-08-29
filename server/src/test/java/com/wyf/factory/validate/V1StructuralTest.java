package com.wyf.factory.validate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.stations.ExtractResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1 结构校验测试：golden（template/src/data/content.json，仓库单源不复制）必须全绿；
 * 每条规则 ≥1 个程序化变形打中。变形 = 读 golden JSON 树改字段再绑定 ContentJson。
 */
class V1StructuralTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path goldenFile;

    private final V1Structural validator = new V1Structural();

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
    @DisplayName("golden 全绿")
    void goldenPasses() throws Exception {
        var result = validator.validate(ctx(loadGolden()));

        assertThat(result.pass()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    @DisplayName("规则1：aspect 非 16:9 / problemType 越界 → V1/meta")
    void metaRules() throws Exception {
        var bad = mutate(root -> {
            ((ObjectNode) root.get("meta")).put("aspect", "9:16");
            ((ObjectNode) root.get("meta")).put("problemType", "选择题");
        });

        var result = validator.validate(ctx(bad));

        assertThat(result.pass()).isFalse();
        assertThat(result.errors()).anyMatch(e -> e.startsWith("V1/meta:") && e.contains("9:16"));
        assertThat(result.errors()).anyMatch(e -> e.startsWith("V1/meta:") && e.contains("选择题"));
    }

    @Test
    @DisplayName("规则2：act=5 → V1/act")
    void actOutOfRange() throws Exception {
        var bad = mutate(root -> scene(root, 0).put("act", 5));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/act:") && e.contains("s01"));
    }

    @Test
    @DisplayName("规则3：未知组件 → V1/组件白名单")
    void unknownComponent() throws Exception {
        var bad = mutate(root -> scene(root, 2).put("component", "quiz-card"));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/组件白名单:") && e.contains("quiz-card"));
    }

    @Test
    @DisplayName("超集（contract.ts ACT_COMPONENTS）：act4 出现 step-card → V1/act组件")
    void componentNotAllowedInAct() throws Exception {
        var bad = mutate(root -> scene(root, 14).put("component", "step-card"));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/act组件:") && e.contains("step-card"));
    }

    @Test
    @DisplayName("规则4a：act2 首场不是 problem-card → V1/act2首场")
    void act2FirstSceneMustBeProblemCard() throws Exception {
        var bad = mutate(root -> scene(root, 0).put("component", "knowledge-card"));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/act2首场:"));
    }

    @Test
    @DisplayName("超集（contract.ts）：problem-card 出现在非首场 → V1/act2首场")
    void problemCardOnlyAtFirstScene() throws Exception {
        var bad = mutate(root -> scene(root, 1).put("component", "problem-card"));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/act2首场:") && e.contains("s02"));
    }

    @Test
    @DisplayName("规则4b：act2 无 knowledge-card → V1/act2知识点")
    void act2RequiresKnowledgeCard() throws Exception {
        var bad = mutate(root -> truncateScenes(root, 1));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/act2知识点:"));
    }

    @Test
    @DisplayName("规则5：无 act3 / act4 场景 → V1/幕覆盖（两条）")
    void act3AndAct4Required() throws Exception {
        var bad = mutate(root -> truncateScenes(root, 4));

        var errors = errorsOf(bad);
        assertThat(errors).anyMatch(e -> e.startsWith("V1/幕覆盖:") && e.contains("act3"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/幕覆盖:") && e.contains("act4"));
    }

    @Test
    @DisplayName("规则6：knowledge 5 条 / pitfalls 4 条越界 → V1/条数")
    void countsOutOfRange() throws Exception {
        var bad = mutate(root -> {
            var knowledge = (ArrayNode) root.get("knowledge");
            knowledge.add(knowledge.get(0).deepCopy()).add(knowledge.get(0).deepCopy());
            var pitfalls = (ArrayNode) root.get("pitfalls");
            pitfalls.add(pitfalls.get(0).deepCopy()).add(pitfalls.get(0).deepCopy());
        });

        var errors = errorsOf(bad);
        assertThat(errors).anyMatch(e -> e.startsWith("V1/条数:") && e.contains("knowledge 条数 5"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/条数:") && e.contains("pitfalls 条数 4"));
    }

    @Test
    @DisplayName("规则7a：popup 未紧跟同 stepRef 的 step-card（前置）→ V1/popup紧跟")
    void popupMustFollowItsStepCard() throws Exception {
        var bad = mutate(root -> swapScenes(root, 4, 5));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/popup紧跟:") && e.contains("s06"));
    }

    @Test
    @DisplayName("规则7b：同 stepRef 两张 step-card → V1/step-card唯一")
    void stepCardPerRefAtMostOne() throws Exception {
        var bad = mutate(root -> props(root, 6).put("stepRef", 1));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/step-card唯一:"));
    }

    @Test
    @DisplayName("规则7c：stepRef 序列跳步 → V1/stepRef序列")
    void stepRefSequenceStrictlyIncreasing() throws Exception {
        var bad = mutate(root -> props(root, 6).put("stepRef", 4));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/stepRef序列:"));
    }

    @Test
    @DisplayName("规则7d：checklist-card 两场 → V1/checklist唯一")
    void checklistAtMostOneScene() throws Exception {
        var bad = mutate(root -> scene(root, 12).put("component", "checklist-card"));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/checklist唯一:"));
    }

    @Test
    @DisplayName("规则7e：act4 itemRef 跳号 → V1/itemRef序列")
    void itemRefSequenceConsecutive() throws Exception {
        var bad = mutate(root -> props(root, 15).put("itemRef", 3));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/itemRef序列:"));
    }

    @Test
    @DisplayName("规则8：act3 场景插进 act2 区 → V1/幕顺序")
    void actOrderNonDecreasing() throws Exception {
        var bad = mutate(root -> moveScene(root, 4, 1));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/幕顺序:"));
    }

    @Test
    @DisplayName("超集（contract.ts）：ttsText 为空 → V1/ttsText")
    void ttsTextBlank() throws Exception {
        var bad = mutate(root -> scene(root, 0).put("ttsText", "  "));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/ttsText:") && e.contains("s01"));
    }

    @Test
    @DisplayName("超集（contract.ts）：popup 缺 formula → V1/popupFormula")
    void popupRequiresFormula() throws Exception {
        var bad = mutate(root -> props(root, 5).remove("formula"));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V1/popupFormula:") && e.contains("s06"));
    }

    @Test
    @DisplayName("规则9：散文字段注入 LaTeX（\\命令、^{、_{）→ V1/散文LaTeX 逐字段报路径")
    void proseFieldsRejectLatex() throws Exception {
        var bad = mutate(root -> {
            problemTextSegment(root, 0, 0).put("value", "已知函数 \\frac{a}{b} ");
            knowledge(root, 2).put("claim", "分离参数后要求 g(x)_{\\max} 可求");
            knowledge(root, 1).put("premise", "判别式 \\frac{b}{2} 需先确认符号");
            step(root, 0).put("statement", "对 f(x)=x^{3}+ax^{2}+x 逐项求导");
            step(root, 1).put("note", "判别式 \\Delta\\le 0 时取等");
            pitfall(root, 0).put("why", "漏掉 \\sqrt{3} 两个端点");
            pitfall(root, 1).put("claim", "直接开方 x_{1} 只写正支");
            methodItem(root, 0).put("step", "识别单调 \\iff 导数定号");
            methodItem(root, 1).put("trick", "上判别式 \\Delta");
            scene(root, 0).put("ttsText", "求出 \\sqrt{3} 的取值范围");
        });

        var errors = errorsOf(bad);
        assertThat(errors).anyMatch(e -> e.startsWith("V1/散文LaTeX:")
                && e.contains("problem.lines[0].segments[0].value"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/散文LaTeX:") && e.contains("knowledge[2].claim"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/散文LaTeX:") && e.contains("knowledge[1].premise"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/散文LaTeX:") && e.contains("steps[0].statement")
                && e.contains("^{"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/散文LaTeX:") && e.contains("steps[1].note"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/散文LaTeX:") && e.contains("pitfalls[0].why"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/散文LaTeX:") && e.contains("pitfalls[1].claim"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/散文LaTeX:") && e.contains("generalMethod[0].step"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/散文LaTeX:") && e.contains("generalMethod[1].trick"));
        assertThat(errors).anyMatch(e -> e.startsWith("V1/散文LaTeX:") && e.contains("scenes[0].ttsText"));
    }

    @Test
    @DisplayName("规则9放行：Unicode 简易数学（±√≤≥⇔、f'(x)>0、a²）不算 LaTeX")
    void unicodeMathInProsePasses() throws Exception {
        var fine = mutate(root -> {
            problemTextSegment(root, 0, 0).put("value", "已知函数 f(x)（参数 a≥0） ");
            knowledge(root, 0).put("claim", "导数恒 ≥0 ⇔ 单调递增（a² 系数为正）");
            knowledge(root, 1).put("premise", "二次项系数 |a|≤√3 时仍需先看符号");
            step(root, 0).put("statement", "对 f(x) 求导，x² 项系数为 3");
            step(root, 1).put("note", "a²≤3 等价于 |a|≤√3");
            pitfall(root, 0).put("why", "漏掉 a=±√3 两个端点");
            pitfall(root, 1).put("claim", "把条件写成 f'(x)>0 严格大于");
            methodItem(root, 0).put("step", "识别：可导函数 + 区间单调（⇔ 导数定号）");
            methodItem(root, 1).put("trick", "能分离参数就分离 → 判别式 ≤ 0");
            scene(root, 0).put("ttsText", "3x 方加 2ax 加 1，判别式 ≤ 0，a=±√3");
        });

        var result = validator.validate(ctx(fine));

        assertThat(result.pass()).as("Unicode 简易数学不应误伤，实际错误：%s", result.errors()).isTrue();
        assertThat(result.errors()).noneMatch(e -> e.startsWith("V1/散文LaTeX:"));
    }

    // ---- helpers ----

    private static ObjectNode knowledge(ObjectNode root, int index) {
        return (ObjectNode) root.get("knowledge").get(index);
    }

    private static ObjectNode step(ObjectNode root, int index) {
        return (ObjectNode) root.get("steps").get(index);
    }

    private static ObjectNode pitfall(ObjectNode root, int index) {
        return (ObjectNode) root.get("pitfalls").get(index);
    }

    private static ObjectNode methodItem(ObjectNode root, int index) {
        return (ObjectNode) root.get("generalMethod").get(index);
    }

    /** 题干某行某段的段节点（type="text" 的散文字段）。 */
    private static ObjectNode problemTextSegment(ObjectNode root, int line, int segment) {
        return (ObjectNode) root.get("problem").get("lines").get(line).get("segments").get(segment);
    }

    private ContentJson loadGolden() throws Exception {
        return MAPPER.readValue(goldenFile.toFile(), ContentJson.class);
    }

    /** 读 golden 树 → 程序化变形 → 绑定 ContentJson。 */
    private static ContentJson mutate(Consumer<ObjectNode> edit) throws Exception {
        ObjectNode root = (ObjectNode) MAPPER.readTree(goldenFile.toFile());
        edit.accept(root);
        return MAPPER.treeToValue(root, ContentJson.class);
    }

    private static ValidationContext ctx(ContentJson content) {
        return new ValidationContext(content, extractOf(content));
    }

    private static ExtractResult extractOf(ContentJson content) {
        return new ExtractResult(content.meta().problemType(), content.problem().lines().stream()
                .map(line -> new ExtractResult.Line(line.id(), line.segments().stream()
                        .map(seg -> new ExtractResult.Seg(seg.type(), seg.value())).toList()))
                .toList());
    }

    private java.util.List<String> errorsOf(ContentJson content) throws Exception {
        var result = validator.validate(ctx(content));
        assertThat(result.pass()).as("变形应被打中，实际错误：%s", result.errors()).isFalse();
        return result.errors();
    }

    private static ArrayNode scenes(ObjectNode root) {
        return (ArrayNode) root.get("scenes");
    }

    private static ObjectNode scene(ObjectNode root, int index) {
        return (ObjectNode) scenes(root).get(index);
    }

    private static ObjectNode props(ObjectNode root, int index) {
        return (ObjectNode) scene(root, index).get("props");
    }

    /** 只保留前 n 场。 */
    private static void truncateScenes(ObjectNode root, int n) {
        var arr = scenes(root);
        for (int i = arr.size() - 1; i >= n; i--) {
            arr.remove(i);
        }
    }

    private static void swapScenes(ObjectNode root, int i, int j) {
        var arr = scenes(root);
        JsonNode tmp = arr.get(i).deepCopy();
        arr.set(i, arr.get(j).deepCopy());
        arr.set(j, tmp);
    }

    /** 把 from 场移动到 to 位置。 */
    private static void moveScene(ObjectNode root, int from, int to) {
        var arr = scenes(root);
        JsonNode moved = arr.remove(from).deepCopy();
        arr.insert(to, moved);
    }
}
