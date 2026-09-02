package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分片 prompt 漂移守护（T6 评审 M 项起源，T18 分片生成重构<b>有意更新</b>，T20b 再重录：
 * COORDINATOR 末尾追加题型骨架段，T23 三重重录：EXTRACT 追加选项成行/长句拆行规则与
 * 选择题 few-shot、PROBLEM_SLICE 末尾追加行宽预算段（R1 修复轮：行数条款改为「以输入为
 * 准、不得自行增删行」，与保真红线/工位校验对齐），T29 事故驱动重录：MATERIAL 追加步骤卡
 * 公式宽度规则、SCENE popup formula 加上限 31 改写条款（golden few-shot 示例段字节零动），
 * 其余常量 golden few-shot 示例段零变化断言保留）：
 * golden（封版模板 template/src/data/content.json，只读单源）few-shot 必须在分片 prompt
 * 中逐字注入——题干片吃 golden problem 段、素材片吃 golden 四段素材、场景片吃 golden
 * scenes 切片（s10..s14）、协调者吃 golden 派生骨架（条数/锚点/全部场景 id）。
 * 另守卡片文字硬约束段（自 SCRIPT prompt 迁入 MATERIAL——约束的字段全属 P2 产出）。
 */
class PromptsDriftGuardTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path goldenFile;

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
    @DisplayName("PROBLEM_SLICE 与 golden 同步：题干首行 text 段逐字注入（T23 有意重录：末尾追加行宽预算段，golden few-shot 零变化断言保留）")
    void problemSlicePromptStaysInSyncWithGolden() throws Exception {
        JsonNode firstLine = MAPPER.readTree(goldenFile.toFile()).path("problem").path("lines").path(0);
        List<String> textValues = new ArrayList<>();
        for (JsonNode seg : firstLine.path("segments")) {
            if ("text".equals(seg.path("type").asText())) {
                textValues.add(seg.path("value").asText());
            }
        }
        assertThat(textValues).as("golden 首行应存在 text 段").isNotEmpty();
        for (String value : textValues) {
            assertThat(Prompts.PROBLEM_SLICE).as("题干片 few-shot 应含 golden 首行文本 %s", value).contains(value);
        }
    }

    @Test
    @DisplayName("T23：EXTRACT 追加选项成行/长句拆行两条规则 + 选择题 few-shot 示例（L1 题干含数学段、L2..L5 选项各一行）")
    void extractPromptCarriesOptionRowAndWrapRules() {
        assertThat(Prompts.EXTRACT)
                .as("选项行规则（事故 0a988be5：整题被压成一行 → V1 拦杀 4 轮白烧）")
                .contains("- 选项行：选择题的选项（A. B. C. D. 等）每项必须独立成行，禁止与题干同行、禁止多选项挤一行")
                .as("行长规则（观感 40 汉字口径，宁多勿挤）")
                .contains("- 行长：每行是画面上的一行排版，长句必须拆行（观感每行不超过约 40 个汉字），拆行永远合法、宁多勿挤")
                .as("选择题 few-shot 示例存在：四个选项 A./B./C./D. 各占一行（L2..L5）")
                .contains("\"id\": \"L2\", \"segments\": [{\"type\": \"text\", \"value\": \"A. \"}")
                .contains("\"id\": \"L3\", \"segments\": [{\"type\": \"text\", \"value\": \"B. \"}")
                .contains("\"id\": \"L4\", \"segments\": [{\"type\": \"text\", \"value\": \"C. \"}")
                .contains("\"id\": \"L5\", \"segments\": [{\"type\": \"text\", \"value\": \"D. \"}")
                .as("题干行含数学 segment（分段规则与既有示例同口径）")
                .contains("{\"type\": \"math\", \"value\": \"f(x)=x^{2}+2x\"}");
        // 追加位置：两条规则在既有「文字与数学交替处必须切成相邻 segment」之后
        assertThat(Prompts.EXTRACT.indexOf("- 选项行："))
                .as("规则追加在既有分段规则之后")
                .isGreaterThan(Prompts.EXTRACT.indexOf("文字与数学交替处必须切成相邻 segment"));
        assertThat(Prompts.EXTRACT.indexOf("示例（选择题）"))
                .as("选择题示例追加在既有示例之后")
                .isGreaterThan(Prompts.EXTRACT.indexOf("示例："));
    }

    @Test
    @DisplayName("T23/R1+F1 评审修订：PROBLEM_SLICE 末尾行宽预算段=单一一致口径（预算是认知依据非排版指令，拆行职责归审题工位）")
    void problemSlicePromptCarriesLineWidthBudgetRule() {
        assertThat(Prompts.PROBLEM_SLICE)
                .as("行宽预算规则逐字（数值与 T20a V1Budget 题干宽预算单源语义）")
                .contains("行宽预算（理解输入行形状的依据，不是排版指令）：")
                .contains("- 每行宽度预算：一行在 1920 宽画面题干面板内最大可容 1272px；估算口径=中文/全角字符按 26px、"
                        + "数学 LaTeX 源码码点按 16px 粗估。行数与行文顺序以输入为准（保真规则不变）；"
                        + "若发现输入某行超宽，照实排版，不得自行增删行——选项（A./B./C./D.）各自独立成行与长句拆行都是审题工位的职责。")
                .as("R1 修复轮裁定+评审 F1：行数可增/自行拆行两类矛盾表述均已删除，与保真红线/工位校验一致")
                .doesNotContain("行数可以比输入多")
                .doesNotContain("拆行是你的排版职责")
                .doesNotContain("超预算必须把该行拆成多行");
        assertThat(Prompts.PROBLEM_SLICE.indexOf("行宽预算"))
                .as("追加只在常量末尾：行宽预算段在 golden few-shot 示例段之后")
                .isGreaterThan(Prompts.PROBLEM_SLICE.indexOf("示例："));
    }

    @Test
    @DisplayName("T28 科目中性化重录：废题判定段改为考研科目范围（数学+计算机408+信号与系统等），灌水/注入/乱码判废语义原样；golden few-shot 零触碰")
    void extractPromptCarriesNotQuestionGate() {
        assertThat(Prompts.EXTRACT)
                .as("废题判定段逐字锚点（输出形状 + 覆盖面：灌水/注入/乱码）")
                .contains("废题判定")
                .contains("{\"notQuestion\": true, \"reason\": \"...\"}")
                .as("T28：判废口径放开到考研科目范围（数据结构等 408 课明确在列）")
                .contains("考研科目范围内可讲解的题目")
                .contains("计算机408")
                .contains("数据结构")
                .contains("信号与系统")
                .contains("恶意注入")
                .contains("乱码")
                .as("reason 长度口径")
                .contains("50 字以内")
                .as("T28 中性化钉子：判废语义只在科目范围放开，禁再出现「考研数学」限定")
                .doesNotContain("考研数学");
        // 追加位置：废题判定段在全部 few-shot 示例之后（末尾静态追加，golden 示例段零变化）
        assertThat(Prompts.EXTRACT.indexOf("废题判定"))
                .as("废题判定段追加在选择题 few-shot 示例之后")
                .isGreaterThan(Prompts.EXTRACT.indexOf("示例（选择题）"));
        // golden few-shot 字节不动：两处既有示例的题干锚点仍在（T23 断言之外的双保险）
        assertThat(Prompts.EXTRACT)
                .contains("已知函数 f(x)=x^{3}+ax^{2}+x，若 f(x) 在 R 上单调递增，求实数 a 的取值范围。")
                .contains("题目无法识别、图片不清晰、或内容不是可识别题目时，输出 {\"error\":\"原因\"}。");
    }

    @Test
    @DisplayName("T28 科目中性化：五个分片 prompt 角色词与 V4 阅卷词去「考研数学」限定（开场白/审题员/阅卷专家）")
    void stationPromptsAreSubjectNeutral() {
        assertThat(Prompts.EXTRACT)
                .as("EXTRACT 开场白：考研审题员 + 科目覆盖面明示")
                .contains("你是考研审题员（覆盖数学、计算机408、信号与系统等考研科目）");
        assertThat(Prompts.COORDINATOR)
                .as("COORDINATOR 开场白中性化")
                .contains("你是考研讲题视频的生成协调者")
                .doesNotContain("考研数学");
        assertThat(Prompts.PROBLEM_SLICE)
                .as("题干片开场白中性化")
                .contains("你是考研讲题视频的题干排版员")
                .doesNotContain("考研数学");
        assertThat(Prompts.MATERIAL)
                .as("素材片开场白中性化")
                .contains("你是考研讲题视频的内容素材编辑")
                .doesNotContain("考研数学");
        assertThat(Prompts.SCENE)
                .as("场景片开场白中性化")
                .contains("你是考研讲题视频的场景分镜师")
                .doesNotContain("考研数学");
    }

    @Test
    @DisplayName("SCENE 与 golden/规则同步：golden scenes 切片（s10..s14）逐字注入 + 7 组件短语都在")
    void scenePromptStaysInSyncWithGoldenAndRules() throws Exception {
        JsonNode golden = MAPPER.readTree(goldenFile.toFile());
        for (JsonNode scene : golden.path("scenes")) {
            String id = scene.path("id").asText();
            if (List.of("s10", "s11", "s12", "s13", "s14").contains(id)) {
                assertThat(Prompts.SCENE).as("场景片 few-shot 应含 golden 场景 %s 的 ttsText", id)
                        .contains(scene.path("ttsText").asText());
            }
        }
        assertThat(Prompts.SCENE)
                .contains("problem-card")
                .contains("knowledge-card")
                .contains("step-card")
                .contains("derivation-popup")
                .contains("pitfall-card")
                .contains("checklist-card")
                .contains("general-list")
                .contains("itemRef");
    }

    @Test
    @DisplayName("COORDINATOR 与 golden 同步：条数计划/锚点指派/全部场景 id 逐字注入（T20b 有意重录：末尾追加题型骨架段，golden 示例段零变化）")
    void coordinatorPromptStaysInSyncWithGolden() throws Exception {
        JsonNode golden = MAPPER.readTree(goldenFile.toFile());
        assertThat(Prompts.COORDINATOR)
                .contains("\"knowledge\":3").contains("\"steps\":5")
                .contains("\"pitfalls\":2").contains("\"generalMethod\":3")
                .contains("\"anchors\":[\"L1\",\"L2\",\"L2\",\"L3\",\"L3\"]");
        for (JsonNode scene : golden.path("scenes")) {
            assertThat(Prompts.COORDINATOR)
                    .as("协调者 few-shot 应含 golden 场景 %s", scene.path("id").asText())
                    .contains(scene.path("id").asText());
        }
        // T18.1 重录：golden few-shot 的 scenes 数组携带计划级 stepRef（s05:1 s06 popup:1
        // s07:2 s08:3 s09 popup:3 s10:4 s11:5；非 step 场景不加字段），并硬化 stepRef 规则段
        assertThat(Prompts.COORDINATOR)
                .as("step-card/derivation-popup 必须带 stepRef 的规则段")
                .contains("step-card 与 derivation-popup 必须带 stepRef")
                .as("golden step-card 逐字带 stepRef")
                .contains("\"id\":\"s05\",\"act\":3,\"component\":\"step-card\",\"stepRef\":1")
                .contains("\"id\":\"s07\",\"act\":3,\"component\":\"step-card\",\"stepRef\":2")
                .contains("\"id\":\"s08\",\"act\":3,\"component\":\"step-card\",\"stepRef\":3")
                .contains("\"id\":\"s10\",\"act\":3,\"component\":\"step-card\",\"stepRef\":4")
                .contains("\"id\":\"s11\",\"act\":3,\"component\":\"step-card\",\"stepRef\":5")
                .as("golden popup 逐字带同 stepRef 且紧跟")
                .contains("\"id\":\"s06\",\"act\":3,\"component\":\"derivation-popup\",\"stepRef\":1")
                .contains("\"id\":\"s09\",\"act\":3,\"component\":\"derivation-popup\",\"stepRef\":3")
                .as("非 step 场景不带 stepRef（problem-card 形态无该字段）")
                .contains("{\"id\":\"s01\",\"act\":2,\"component\":\"problem-card\"}");
        // T20b 重录：题型骨架段只做末尾静态追加——golden few-shot 示例段（counts/anchors/全场景
        // id，上文全部断言）与规则主体一字未动；追加段四小节逐字在位，且在 golden 示例段之后。
        assertThat(Prompts.COORDINATOR)
                .as("规则主体零变化：全局硬性范围行保持（题型差异只在追加段与 SkeletonLibrary 规格表）")
                .contains("counts 条数硬性范围：knowledge 2-4 条 / steps 3-10 条 / pitfalls 1-3 条 / generalMethod 3-6 条")
                .as("题型骨架段四小节逐字")
                .contains("题型骨架段（按输入题目的 problemType 适用对应小节）：")
                .contains("- 基础题：单知识点、推导直白，步骤 3-6 条从简；derivation-popup 只配最关键的一步。")
                .contains("- 计算题：按上文通用规则与示例执行。")
                .contains("- 证明题：逻辑链完整不跳步，步骤至少 4 条；每个 step-card 都配 derivation-popup 展示该步依据。")
                .contains("- 应用题：前两步通常是设参/建模，步骤 4-8 条；generalMethod 侧重建模通法而非纯计算技巧。");
        assertThat(Prompts.COORDINATOR.indexOf("题型骨架段"))
                .as("追加只在常量末尾：题型骨架段在 golden 示例段（JSON）之后")
                .isGreaterThan(Prompts.COORDINATOR.indexOf("示例（golden 题："));
    }

    @Test
    @DisplayName("MATERIAL 与 golden 同步：知识点/推导 golden 文本逐字注入（四段素材 few-shot 保持）")
    void materialPromptStaysInSyncWithGolden() throws Exception {
        JsonNode golden = MAPPER.readTree(goldenFile.toFile());
        assertThat(Prompts.MATERIAL)
                .contains(golden.path("knowledge").path(0).path("claim").asText())
                .as("few-shot 内嵌的是 JSON 转义形态（\\ → \\\\），逐字等价")
                .contains(jsonEscaped(golden.path("steps").path(0).path("derivation").asText()))
                .contains(jsonEscaped(golden.path("steps").path(golden.path("steps").size() - 1).path("derivation").asText()));
    }

    /** golden 值 → prompt few-shot 内嵌形态（JSON 字符串转义：反斜杠翻倍）。 */
    private static String jsonEscaped(String value) {
        return value.replace("\\", "\\\\");
    }

    @Test
    @DisplayName("Ruling-17：MATERIAL 含结论卡/卡片文字硬约束（可执行、可数：单行短式/字数上限/禁止长分式；T18 自 SCRIPT 迁入）")
    void materialPromptCarriesCardTextHardConstraints() {
        assertThat(Prompts.MATERIAL)
                .as("结论卡硬约束段存在")
                .contains("卡片文字硬约束")
                .contains("结论卡")
                .contains("steps 最后一条的 derivation")
                .as("结论卡只允许一行短式 + 字数上限")
                .contains("一行短式")
                .contains("≤ 40")
                .as("参考形状（R2 修复目标：a\\in[-\\sqrt{3},\\ \\sqrt{3}] 单行短式）")
                .contains("a\\in[-\\sqrt{3},\\ \\sqrt{3}]")
                .as("禁止多行推导/换行/长分式")
                .contains("禁止多行推导")
                .contains("\\begin{aligned}")
                .contains("长分式")
                .contains("\\frac")
                .as("标签字数上限")
                .contains("≤6 字标签")
                .as("R2 实证反例（等号后折行）")
                .contains("等号后折行");
    }

    @Test
    @DisplayName("T18：三条新生成规则进分片 prompt（并列条件拆分/推导逐步自验/口播与画面公式逐符号一致；T28 「并列数学条件」中性化为「并列条件」）")
    void shardPromptsCarryThreeNewGenerationRules() {
        assertThat(Prompts.COORDINATOR)
                .contains("并列条件必须拆成多步/多条")
                .contains("逐步自验");
        assertThat(Prompts.MATERIAL)
                .contains("并列条件必须拆成多行/多段")
                .contains("推导逐步自验");
        assertThat(Prompts.SCENE)
                .contains("并列条件必须拆成多场/多 popup")
                .contains("推导逐步自验")
                .contains("口播 ttsText 必须与该场景画面公式逐符号一致");
    }

    @Test
    @DisplayName("T29 事故 daf87d4c 重录：MATERIAL 步骤卡 derivation 单行短公式 ≤30 字符（超长拆步）；SCENE popup formula ≤31 字符（默认照抄 derivation，照抄超长改写关键主式）；追加只在指令段，golden few-shot 示例段字节零动")
    void shardPromptsCarryFormulaWidthRules() {
        // MATERIAL：与 V1Budget R-宽度③（steps 上限 60=golden 最长链）同病灶的上游收紧口径
        assertThat(Prompts.MATERIAL)
                .as("步骤卡公式宽度规则逐字（30 字符为收紧目标，反例=事故不等式链形状）")
                .contains("- 每步 derivation 是单行短公式：TeX 源码不超过 30 个字符（如 -\\frac{1}{2} \\le t \\le -\\frac{1}{4} 已是上限长度）；"
                        + "更长的推导必须拆成多个步骤/多条公式，禁止单条塞入整条不等式链或长表达式");
        assertThat(Prompts.MATERIAL.indexOf("每步 derivation 是单行短公式"))
                .as("追加在卡片文字硬约束指令段内、golden few-shot 示例段之前")
                .isLessThan(Prompts.MATERIAL.indexOf("示例（golden 素材"));
        // SCENE：31 与 V1Budget.POPUP_FORMULA_MAX_CODE_POINTS 同数值（改写条款化解与照抄规则的冲突）
        assertThat(Prompts.SCENE)
                .as("popup formula 上限逐字")
                .contains("formula 是单行短公式，TeX 源码不超过 31 个字符（如 -\\frac{1}{2} \\le t \\le -\\frac{1}{4} 已是上限长度）")
                .as("默认照抄保留（口播/画面一致机制锚点），超长改写有出路（golden s09 关键主式先例）")
                .contains("默认逐字符照抄 steps[N-1].derivation")
                .contains("照抄会超出 31 个字符时改写为该步推导的关键主式")
                .contains("禁止整条塞入长不等式链");
        assertThat(Prompts.SCENE.indexOf("不超过 31 个字符"))
                .as("修订只在 props 规则指令段、golden few-shot 示例段之前")
                .isLessThan(Prompts.SCENE.indexOf("示例（golden 题"));
    }

    @Test
    @DisplayName("golden 合规回归：结论卡/generalMethod/pitfalls 文字满足 MATERIAL 硬约束数值")
    void goldenContentSatisfiesCardTextHardConstraints() throws Exception {
        JsonNode golden = MAPPER.readTree(goldenFile.toFile());

        // 结论卡 = steps 最后一条的 derivation：单行短式 ≤ 40 字符，无多行推导/长分式/aligned
        JsonNode steps = golden.path("steps");
        assertThat(steps.size()).as("golden steps 非空").isGreaterThan(0);
        String conclusion = steps.path(steps.size() - 1).path("derivation").asText();
        assertThat(conclusion).as("结论卡 derivation ≤ 40 字符（实测 %d）", conclusion.length())
                .hasSizeLessThanOrEqualTo(40);
        assertThat(conclusion)
                .as("结论卡只允许单行短式")
                .doesNotContain("\n")
                .doesNotContain("\\frac")
                .doesNotContain("\\begin")
                .doesNotContain("aligned");

        for (JsonNode item : golden.path("generalMethod")) {
            String step = item.path("step").asText();
            int labelEnd = step.indexOf('：');
            assertThat(labelEnd).as("step 以「标签：说明」开头：%s", step).isGreaterThan(0);
            assertThat(labelEnd).as("标签 ≤ 6 字（%s）", step).isLessThanOrEqualTo(6);
            assertThat(step.length()).as("step 整行 ≤ 24 字（%s，实测 %d）", step, step.length())
                    .isLessThanOrEqualTo(24);
            assertThat(item.path("trick").asText().length())
                    .as("trick ≤ 40 字（%s）", item.path("trick").asText()).isLessThanOrEqualTo(40);
        }

        for (JsonNode pitfall : golden.path("pitfalls")) {
            assertThat(pitfall.path("claim").asText().length())
                    .as("claim ≤ 20 字（%s）", pitfall.path("claim").asText()).isLessThanOrEqualTo(20);
            assertThat(pitfall.path("why").asText().length())
                    .as("why ≤ 40 字（%s）", pitfall.path("why").asText()).isLessThanOrEqualTo(40);
        }
    }
}
