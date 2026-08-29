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
}
