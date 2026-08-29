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
 * SCRIPT prompt 漂移守护（T6 评审 M 项：few-shot 硬编码在常量里，生成脚本未入库，
 * golden/规则改版时可能静默失同步）：断言 SCRIPT 仍含 golden 题干首行的 text 值子串
 * 与 7 组件规则关键短语。golden 直接读 ../template/src/data/content.json（单源）。
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
    @DisplayName("SCRIPT 与 golden/规则同步：首行 text 子串 + 7 组件短语都在")
    void scriptPromptStaysInSyncWithGoldenAndRules() throws Exception {
        JsonNode firstLine = MAPPER.readTree(goldenFile.toFile()).path("problem").path("lines").path(0);
        List<String> textValues = new ArrayList<>();
        for (JsonNode seg : firstLine.path("segments")) {
            if ("text".equals(seg.path("type").asText())) {
                textValues.add(seg.path("value").asText());
            }
        }
        assertThat(textValues).as("golden 首行应存在 text 段").isNotEmpty();
        for (String value : textValues) {
            assertThat(Prompts.SCRIPT).as("SCRIPT few-shot 应含 golden 首行文本 %s", value).contains(value);
        }

        assertThat(Prompts.SCRIPT)
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
    @DisplayName("Ruling-17：SCRIPT 含结论卡/卡片文字硬约束（可执行、可数：单行短式/字数上限/禁止长分式）")
    void scriptPromptCarriesCardTextHardConstraints() {
        assertThat(Prompts.SCRIPT)
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
    @DisplayName("golden 合规回归：结论卡/generalMethod/pitfalls 文字满足 SCRIPT 硬约束数值")
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
