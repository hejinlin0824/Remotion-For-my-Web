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
 * 分片 prompt 漂移守护（T6 评审 M 项起源，T18 分片生成重构<b>有意更新</b>）：
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
    @DisplayName("PROBLEM_SLICE 与 golden 同步：题干首行 text 段逐字注入")
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
    @DisplayName("COORDINATOR 与 golden 同步：条数计划/锚点指派/全部场景 id 逐字注入（T18.1 有意重录：+stepRef 计划级分派）")
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
    @DisplayName("T18：三条新生成规则进分片 prompt（并列条件拆分/推导逐步自验/口播与画面公式逐符号一致）")
    void shardPromptsCarryThreeNewGenerationRules() {
        assertThat(Prompts.COORDINATOR)
                .contains("并列数学条件必须拆成多步/多条")
                .contains("逐步自验");
        assertThat(Prompts.MATERIAL)
                .contains("并列数学条件必须拆成多行/多段")
                .contains("推导逐步自验");
        assertThat(Prompts.SCENE)
                .contains("并列数学条件必须拆成多场/多 popup")
                .contains("推导逐步自验")
                .contains("口播 ttsText 必须与该场景画面公式逐符号一致");
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
