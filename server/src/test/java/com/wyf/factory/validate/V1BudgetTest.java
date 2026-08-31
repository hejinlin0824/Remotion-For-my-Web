package com.wyf.factory.validate;

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
 * V1 预算校验测试（T20a）：golden 直读过 V1 全绿（新规则零误杀）；三组规则各违规变体
 * （逐字断言消息，消息自带路由令牌）；边界等价值（题干宽 W 恰 2120=scale 0.6 不违规 /
 * 2121 级别违规；字数恰等上限不违规；\frac 分子恰 4 字符不违规；列表 scale 在预算与
 * 下限之间不违规）。
 */
class V1BudgetTest {

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
    @DisplayName("golden 全绿：直读过 V1（含 T20a 预算规则）零误杀")
    void goldenPasses() throws Exception {
        var result = validator.validate(ctx(loadGolden()));

        assertThat(result.pass()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    // ---- R-宽度① 题干行宽 ----

    @Test
    @DisplayName("R-宽度①边界：题干行宽恰 2120px（scale=0.6）不违规")
    void problemLine_exactFloorWidth_passes() throws Exception {
        // 38 CJK ×50 + 8 空格 ×27.5 = 1900 + 220 = 2120px，scale = 1272/2120 = 0.6 恰在下限
        var fine = mutate(root -> replaceLineSegments(root, 0, "text", "识".repeat(38) + " ".repeat(8)));

        var result = validator.validate(ctx(fine));

        assertThat(result.pass()).as("恰 2120px 不应违规，实际错误：%s", result.errors()).isTrue();
        assertThat(result.errors()).noneMatch(e -> e.startsWith("V1/题干宽:"));
    }

    @Test
    @DisplayName("R-宽度①违规：text 段超宽（2147.5px → 缩放 0.592）逐字消息含题干令牌")
    void problemLine_textOverflow_exactMessage() throws Exception {
        var bad = mutate(root -> replaceLineSegments(root, 0, "text", "识".repeat(38) + " ".repeat(9)));

        assertThat(errorsOf(bad)).contains(
                "V1/题干宽: problem.lines[0] 估宽 2148px 超出题干面板预算（缩放 0.592 低于下限 0.6，渲染必溢出）");
    }

    @Test
    @DisplayName("R-宽度①违规：math 段按码点×0.6×46 估宽（2208px → 缩放 0.576）")
    void problemLine_mathOverflow_exactMessage() throws Exception {
        var bad = mutate(root -> replaceLineSegments(root, 0, "math", "f".repeat(80)));

        assertThat(errorsOf(bad)).contains(
                "V1/题干宽: problem.lines[0] 估宽 2208px 超出题干面板预算（缩放 0.576 低于下限 0.6，渲染必溢出）");
    }

    // ---- R-宽度② 列表高度 ----

    @Test
    @DisplayName("R-宽度②边界：通法列表缩放在预算内与下限之间（scale≈0.58 ≥ 0.55）不违规")
    void generalList_scaleBetweenBudgetAndFloor_passes() throws Exception {
        // 3 项各 100 码点 step/trick → needed 1216.8px，scale = 705.6/1216.8 ≈ 0.580，未触下限
        var fine = mutate(root -> {
            for (int i = 0; i < 3; i++) {
                methodItem(root, i).put("step", "识".repeat(100));
                methodItem(root, i).put("trick", "技".repeat(100));
            }
        });

        var result = validator.validate(ctx(fine));

        assertThat(result.errors()).noneMatch(e -> e.startsWith("V1/列表高:"));
        assertThat(result.errors()).as("变形已生效（字数超限照报）")
                .anyMatch(e -> e.equals("V1/字数超限: generalMethod[0].step 长度 100 码点超出上限 24"));
    }

    @Test
    @DisplayName("R-宽度②违规：通法列表（generalMethod 令牌 → P2）逐字消息")
    void generalList_overflow_exactMessage() throws Exception {
        var bad = mutate(root -> {
            for (int i = 0; i < 3; i++) {
                methodItem(root, i).put("step", "识".repeat(120));
                methodItem(root, i).put("trick", "技".repeat(120));
            }
        });

        var budgetErrors = errorsOf(bad).stream().filter(e -> e.startsWith("V1/列表高:")).toList();
        // 仅末场（itemRef=3，全量 3 项）超限；itemRef=1/2 前缀仍在预算内
        assertThat(budgetErrors).containsExactly(
                "V1/列表高: generalMethod[2]（通法列表 itemRef=3）估算高度 1494.0px 超出可用高度 705.6px（缩放 0.472 低于下限 0.55，渲染必溢出）");
    }

    @Test
    @DisplayName("R-宽度②违规：检查清单（pitfalls 令牌 → P2，预算另扣结论卡 220）逐字消息")
    void checklist_overflow_exactMessage() throws Exception {
        var bad = mutate(root -> {
            pitfall(root, 0).put("claim", "错".repeat(200));
            pitfall(root, 1).put("claim", "错".repeat(200));
        });

        var budgetErrors = errorsOf(bad).stream().filter(e -> e.startsWith("V1/列表高:")).toList();

        assertThat(budgetErrors).containsExactly(
                "V1/列表高: pitfalls[0]（检查清单 pitfallRefs=[1, 2]）估算高度 891.2px 超出可用高度 485.6px（缩放 0.545 低于下限 0.55，渲染必溢出）");
    }

    // ---- R-字符① 字数硬约束 ----

    @Test
    @DisplayName("R-字符①：五字段恰等上限不违规 / 超 1 码点违规（逐字消息，含对应路由令牌）")
    void characterLimits_boundaryAndOffByOne() throws Exception {
        // generalMethod.step ≤24
        assertThat(validateOf(mutate(r -> methodItem(r, 0).put("step", "识".repeat(24)))).pass()).isTrue();
        assertThat(errorsOf(mutate(r -> methodItem(r, 0).put("step", "识".repeat(25))))).contains(
                "V1/字数超限: generalMethod[0].step 长度 25 码点超出上限 24");
        // generalMethod.trick ≤40
        assertThat(validateOf(mutate(r -> methodItem(r, 0).put("trick", "技".repeat(40)))).pass()).isTrue();
        assertThat(errorsOf(mutate(r -> methodItem(r, 0).put("trick", "技".repeat(41))))).contains(
                "V1/字数超限: generalMethod[0].trick 长度 41 码点超出上限 40");
        // pitfalls.claim ≤20
        assertThat(validateOf(mutate(r -> pitfall(r, 0).put("claim", "错".repeat(20)))).pass()).isTrue();
        assertThat(errorsOf(mutate(r -> pitfall(r, 0).put("claim", "错".repeat(21))))).contains(
                "V1/字数超限: pitfalls[0].claim 长度 21 码点超出上限 20");
        // pitfalls.why ≤40
        assertThat(validateOf(mutate(r -> pitfall(r, 0).put("why", "漏".repeat(40)))).pass()).isTrue();
        assertThat(errorsOf(mutate(r -> pitfall(r, 0).put("why", "漏".repeat(41))))).contains(
                "V1/字数超限: pitfalls[0].why 长度 41 码点超出上限 40");
        // 结论卡 = steps 末条 derivation ≤40
        assertThat(validateOf(mutate(r -> step(r, 4).put("derivation", "a".repeat(40)))).pass()).isTrue();
        assertThat(errorsOf(mutate(r -> step(r, 4).put("derivation", "a".repeat(41))))).contains(
                "V1/字数超限: steps[4].derivation 长度 41 码点超出上限 40（结论卡）");
    }

    @Test
    @DisplayName("R-字符①长分式：\\frac 分子恰 4 字符不违规 / 5 字符违规 / 分母同理 / 嵌套只查最外层")
    void longFraction_rules() throws Exception {
        // 分子恰 4 字符 → 放行
        assertThat(validateOf(mutate(r -> step(r, 4).put("derivation", "\\frac{abcd}{x}"))).pass()).isTrue();
        // 分子 5 字符 → 违规
        assertThat(errorsOf(mutate(r -> step(r, 4).put("derivation", "\\frac{abcde}{x}")))).contains(
                "V1/字数超限: steps[4].derivation 长分式 \\frac{abcde}{x} 分子 5 码点超出上限 4（结论卡）");
        // 分母 5 字符 → 违规
        assertThat(errorsOf(mutate(r -> step(r, 4).put("derivation", "\\frac{a}{bcdef}")))).contains(
                "V1/字数超限: steps[4].derivation 长分式 \\frac{a}{bcdef} 分母 5 码点超出上限 4（结论卡）");
        // 嵌套 \frac 只查最外层（外层分子 "\frac{ab}{cd}" 计 13 码点 → 1 条违规，内层不另报）
        assertThat(errorsOf(mutate(r -> step(r, 4).put("derivation", "\\frac{\\frac{ab}{cd}}{e}")))
                .stream().filter(e -> e.startsWith("V1/字数超限:")).toList()).containsExactly(
                "V1/字数超限: steps[4].derivation 长分式 \\frac{\\frac{ab}{cd}}{e} 分子 13 码点超出上限 4（结论卡）");
    }

    @Test
    @DisplayName("R-字符①口径：长分式只查结论卡（steps 末条 derivation），中间步骤推导不查")
    void longFraction_onlyLastDerivation() throws Exception {
        var fine = mutate(r -> step(r, 0).put("derivation", "\\frac{abcdefghij}{k}"));

        var result = validator.validate(ctx(fine));

        assertThat(result.pass()).as("非末条 derivation 不属结论卡，实际错误：%s", result.errors()).isTrue();
    }

    // ---- helpers ----

    private static ObjectNode step(ObjectNode root, int index) {
        return (ObjectNode) root.get("steps").get(index);
    }

    private static ObjectNode pitfall(ObjectNode root, int index) {
        return (ObjectNode) root.get("pitfalls").get(index);
    }

    private static ObjectNode methodItem(ObjectNode root, int index) {
        return (ObjectNode) root.get("generalMethod").get(index);
    }

    /** 把题干某行的段序列整体替换为单段（type/value）。 */
    private static void replaceLineSegments(ObjectNode root, int line, String type, String value) {
        ObjectNode seg = MAPPER.createObjectNode();
        seg.put("type", type).put("value", value);
        ArrayNode segs = MAPPER.createArrayNode();
        segs.add(seg);
        ((ObjectNode) root.get("problem").get("lines").get(line)).set("segments", segs);
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

    private ValidationResult validateOf(ContentJson content) throws Exception {
        return validator.validate(ctx(content));
    }

    private java.util.List<String> errorsOf(ContentJson content) throws Exception {
        var result = validator.validate(ctx(content));
        assertThat(result.pass()).as("变形应被打中，实际错误：%s", result.errors()).isFalse();
        return result.errors();
    }
}
