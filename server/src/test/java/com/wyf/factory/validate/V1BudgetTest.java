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
 *
 * <p>T29 追加 R-宽度③ 卡片公式宽度（事故 daf87d4c：长不等式链在步骤卡/推演卡内折行，
 * 场景片重做同病复发——超宽公式便宜地过 V1 到昂贵 QA 才被抓）：derivation 60 码点过 /
 * 61 码点驳、popup formula 31 过 / 32 驳（消息含 steps[i]/场景 id 路由令牌与真实数值）；
 * 另以「预算钉子」测试把两个上限值钉在 golden few-shot 实测最大码点上（单源自证）。</p>
 *
 * <p>T31a 追加 R-宽度④ 公式字段中文码点闸（事故 b91e247a：GLM 把整句中文塞进
 * knowledge[2].formula 的 \text{}（12 中文字），知识卡公式 58 号字右边界爆框截断）：
 * 三类公式字段（steps[].derivation / knowledge[].formula / popup props.formula）中文
 * 恰 5 字（golden 实测最大）过 / 6 字驳 / 事故原式 12 字驳，消息各带路由令牌
 * （steps[i]→P2a、knowledge[i]→P2b、场景 id→场景片）与修复指引。</p>
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
                "V1/题干宽: problem.lines[0] 估宽 2148px 超出题干面板预算（缩放 0.592 低于下限 0.6，渲染必溢出）；修复指引：把该行拆分为多行（选项各占一行）");
    }

    @Test
    @DisplayName("R-宽度①违规：math 段按码点×0.6×46 估宽（2208px → 缩放 0.576）")
    void problemLine_mathOverflow_exactMessage() throws Exception {
        var bad = mutate(root -> replaceLineSegments(root, 0, "math", "f".repeat(80)));

        assertThat(errorsOf(bad)).contains(
                "V1/题干宽: problem.lines[0] 估宽 2208px 超出题干面板预算（缩放 0.576 低于下限 0.6，渲染必溢出）；修复指引：把该行拆分为多行（选项各占一行）");
    }

    @Test
    @DisplayName("R-宽度①边界：W 恰 2121px（text 27 CJK+18 空格 + math 10 码点）→ 缩放 0.5997<0.6 违规")
    void problemLine_exactly2121_violates() throws Exception {
        // 纯 text 段不可达 2121（500a+275b ≡ 0 mod 25，21210 ≡ 10），混 math 段可构造：
        // 27×500 + 18×275 + 10×276 = 21210 tenths = 2121.0px，scale = 1272/2121 ≈ 0.5997 < 0.6。
        // 注：scale 显示走 %.3f → 「0.600」（0.5997 四舍五入），消息格式与既有违规用例同口径。
        var bad = mutate(root -> {
            replaceLineSegments(root, 0, "text", "识".repeat(27) + " ".repeat(18));
            var mathSeg = MAPPER.createObjectNode();
            mathSeg.put("type", "math").put("value", "f".repeat(10));
            ((ArrayNode) root.get("problem").get("lines").get(0).get("segments")).add(mathSeg);
        });

        assertThat(errorsOf(bad)).contains(
                "V1/题干宽: problem.lines[0] 估宽 2121px 超出题干面板预算（缩放 0.600 低于下限 0.6，渲染必溢出）；修复指引：把该行拆分为多行（选项各占一行）");
    }

    @Test
    @DisplayName("R-宽度①驳回消息尾缀（T23）：题干宽违规一律带修复指引（拆行/选项成行），既有「题干」路由令牌不受追加文案影响")
    void problemLine_messageCarriesFixGuidance() throws Exception {
        var bad = mutate(root -> replaceLineSegments(root, 0, "text", "识".repeat(38) + " ".repeat(9)));

        var widthErrors = errorsOf(bad).stream().filter(e -> e.startsWith("V1/题干宽:")).toList();

        assertThat(widthErrors).as("违规照报").isNotEmpty();
        assertThat(widthErrors).allMatch(e -> e.startsWith("V1/题干宽: problem.lines[0] 估宽"),
                "消息头（含路由令牌「题干」）保持原状");
        assertThat(widthErrors).allMatch(e -> e.endsWith("；修复指引：把该行拆分为多行（选项各占一行）"),
                "尾缀修复指引逐字追加在消息末尾");
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

    // ---- R-宽度③ 卡片公式宽度（T29，事故 daf87d4c） ----

    @Test
    @DisplayName("R-宽度③边界：steps derivation 恰 60 码点（golden 最长链同长）不违规 / 61 码点驳（消息含 steps 令牌与真实数值 → P2）")
    void stepDerivation_boundaryAndOffByOne() throws Exception {
        assertThat(validateOf(mutate(r -> step(r, 0).put("derivation", "a".repeat(60)))).pass()).isTrue();
        assertThat(errorsOf(mutate(r -> step(r, 0).put("derivation", "a".repeat(61))))).contains(
                "V1/公式宽: steps[0].derivation 第 1 步公式 61 码点超出 60 上限（步骤卡公式盒装不下，KaTeX 将折行）；请拆成多步/多条公式");
    }

    @Test
    @DisplayName("R-宽度③多步：两步同时超限各报一条、第 i 步序数正确；末步同触 R-字符① 结论卡规则互不吞并")
    void stepDerivation_multipleViolations() throws Exception {
        var bad = mutate(r -> {
            step(r, 1).put("derivation", "b".repeat(65));
            step(r, 4).put("derivation", "c".repeat(70));
        });

        var widthErrors = errorsOf(bad).stream().filter(e -> e.startsWith("V1/公式宽:")).toList();

        assertThat(widthErrors).containsExactly(
                "V1/公式宽: steps[1].derivation 第 2 步公式 65 码点超出 60 上限（步骤卡公式盒装不下，KaTeX 将折行）；请拆成多步/多条公式",
                "V1/公式宽: steps[4].derivation 第 5 步公式 70 码点超出 60 上限（步骤卡公式盒装不下，KaTeX 将折行）；请拆成多步/多条公式");
        assertThat(errorsOf(bad)).contains(
                "V1/字数超限: steps[4].derivation 长度 70 码点超出上限 40（结论卡）");
    }

    @Test
    @DisplayName("R-宽度③边界：derivation-popup formula 恰 31 码点不违规 / 32 码点驳（消息含场景 id+scenes 下标路由令牌 → 场景片）")
    void popupFormula_boundaryAndOffByOne() throws Exception {
        assertThat(validateOf(mutate(r -> popupFormula(r, "s06").put("formula", "a".repeat(31)))).pass()).isTrue();
        assertThat(errorsOf(mutate(r -> popupFormula(r, "s06").put("formula", "a".repeat(32))))).contains(
                "V1/公式宽: scenes[5] s06 derivation-popup formula 32 码点超出 31 上限（推演卡装不下，KaTeX 将折行）；请缩短为该步推导的关键主式");
    }

    @Test
    @DisplayName("R-宽度③预算钉子（T29）：两个上限值钉在 golden few-shot 实测码点上——derivation 上限 60 = golden 最大 derivation 码点数；popup 上限 31 ≥ golden 最大 popup formula 码点数")
    void formulaWidthLimits_pinnedToGoldenFewShot() throws Exception {
        var golden = loadGolden();
        int maxDerivation = golden.steps().stream()
                .mapToInt(s -> codePointsOf(s.derivation())).max().orElse(0);
        int maxPopup = golden.scenes().stream()
                .filter(s -> "derivation-popup".equals(s.component()))
                .mapToInt(s -> codePointsOf(String.valueOf(s.props().get("formula"))))
                .max().orElse(0);

        assertThat(maxDerivation).as("golden 最大 derivation 码点数（45/38/60 中的 60）。"
                + "此钉子先红 = golden few-shot 公式变长 → R-宽度③ 上限须重新推导（否则 golden 回归即红）").isEqualTo(60);
        assertThat(maxPopup).as("golden 最大 popup formula 码点数（18/21 中的 21），31 上限须始终容纳它").isEqualTo(21);
    }

    // ---- R-宽度④ 公式字段中文码点闸（T31a，事故 b91e247a） ----

    /** 事故 b91e247a 原式（knowledge[2].formula 形态：\text{} 内塞整句中文 12 字，58 号字爆框截断）。 */
    private static final String ACCIDENT_FORMULA =
            "f(-2t)\\text{ 为 }f(t)\\text{ 沿纵轴翻转并横轴压缩 }2\\text{ 倍}";

    @Test
    @DisplayName("R-宽度④边界：derivation 中文恰 5 字（golden 实测最大）不违规 / 6 字驳（消息含 steps[i] 令牌与修复指引 → P2a）")
    void formulaCjk_derivationBoundary() throws Exception {
        assertThat(validateOf(mutate(r -> step(r, 0).put("derivation", "在".repeat(5)))).pass()).isTrue();
        assertThat(errorsOf(mutate(r -> step(r, 0).put("derivation", "在".repeat(6))))).contains(
                "V1/公式中文: steps[0].derivation 公式含中文 6 字超出 5 字上限"
                        + "（步骤卡公式 54 号字渲染，中文整句必爆框）；中文说明请写入 statement/note，"
                        + "公式内 \\text{} 只允许 1~4 字衔接词（如 在/且/或）");
    }

    @Test
    @DisplayName("R-宽度④边界：popup formula 中文恰 5 字不违规 / 6 字驳（消息含场景 id 令牌 → 场景片）")
    void formulaCjk_popupBoundary() throws Exception {
        assertThat(validateOf(mutate(r -> popupFormula(r, "s06").put("formula", "在".repeat(5)))).pass()).isTrue();
        assertThat(errorsOf(mutate(r -> popupFormula(r, "s06").put("formula", "在".repeat(6))))).contains(
                "V1/公式中文: scenes[5] s06 derivation-popup formula 公式含中文 6 字超出 5 字上限"
                        + "（推演卡公式 56 号字渲染，中文整句必爆框）；中文说明请写入对应步的 statement/note，"
                        + "公式内 \\text{} 只允许 1~4 字衔接词（如 在/且/或）");
    }

    @Test
    @DisplayName("R-宽度④事故原式：knowledge formula 中文 12 字（b91e247a \\text{} 整句中文）驳回，消息含 knowledge[i] 令牌与修复指引 → P2b")
    void formulaCjk_knowledgeAccidentForm() throws Exception {
        assertThat(cjkCount(ACCIDENT_FORMULA)).as("事故原式中文数（实勘复算钉死）").isEqualTo(12);
        assertThat(errorsOf(mutate(r -> knowledge(r, 2).put("formula", ACCIDENT_FORMULA)))).contains(
                "V1/公式中文: knowledge[2].formula 公式含中文 12 字超出 5 字上限"
                        + "（知识卡公式 58 号字渲染，中文整句右边界爆框截断）；中文说明请写入 claim/premise/trap，"
                        + "公式内 \\text{} 只允许 1~4 字衔接词（如 在/且/或）");
    }

    // ---- helpers ----

    /** 中文码点数（V1Budget.isCjk 惯例口径：0x2e80-0x9fff ∪ 0xf900-0xfaff ∪ 0xff00-0xffef）。 */
    private static int cjkCount(String value) {
        int count = 0;
        for (int cp : value.codePoints().toArray()) {
            if ((cp >= 0x2e80 && cp <= 0x9fff) || (cp >= 0xf900 && cp <= 0xfaff)
                    || (cp >= 0xff00 && cp <= 0xffef)) {
                count++;
            }
        }
        return count;
    }

    /** 码点数（fit.ts [...tex].length 口径）。 */
    private static int codePointsOf(String value) {
        return value == null ? 0 : (int) value.codePoints().count();
    }

    /** 按场景 id 取 derivation-popup 的 props 节点（golden 场景结构固定，测试前提失效即抛）。 */
    private static ObjectNode popupFormula(ObjectNode root, String sceneId) {
        ArrayNode scenes = (ArrayNode) root.get("scenes");
        for (int i = 0; i < scenes.size(); i++) {
            ObjectNode scene = (ObjectNode) scenes.get(i);
            if ("derivation-popup".equals(scene.path("component").asText())
                    && sceneId.equals(scene.path("id").asText())) {
                return (ObjectNode) scene.get("props");
            }
        }
        throw new IllegalStateException("测试前提失效：golden 无 derivation-popup 场景 " + sceneId);
    }

    private static ObjectNode step(ObjectNode root, int index) {
        return (ObjectNode) root.get("steps").get(index);
    }

    private static ObjectNode knowledge(ObjectNode root, int index) {
        return (ObjectNode) root.get("knowledge").get(index);
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
