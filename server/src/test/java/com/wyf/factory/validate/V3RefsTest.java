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
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V3 引用合法测试：stepRef/pitfallRef/itemRef/knowledgeRef 越界、usesAnchor 未知行、
 * 未知 props 键、缺必需键各 ≥1 例；golden 全绿。变形 = 程序化改 golden JSON 树。
 */
class V3RefsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path goldenFile;

    private final V3Refs validator = new V3Refs();

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
    @DisplayName("stepRef=0 / stepRef=6 越界 → V3")
    void stepRefRange() throws Exception {
        var zero = mutate(root -> props(root, 4).put("stepRef", 0));
        var beyond = mutate(root -> props(root, 4).put("stepRef", 6));

        assertThat(errorsOf(zero)).anyMatch(e -> e.startsWith("V3:") && e.contains("s05") && e.contains("stepRef=0"));
        assertThat(errorsOf(beyond)).anyMatch(e -> e.startsWith("V3:") && e.contains("s05") && e.contains("stepRef=6"));
    }

    @Test
    @DisplayName("steps[].usesAnchor 指向不存在行 → V3")
    void usesAnchorUnknown() throws Exception {
        var bad = mutate(root -> ((ObjectNode) root.get("steps").get(0)).put("usesAnchor", "L99"));

        assertThat(errorsOf(bad)).anyMatch(e -> e.contains("steps[0].usesAnchor='L99' 不存在于 problem.lines"));
    }

    @Test
    @DisplayName("props 未知键 → V3 警告性错误")
    void unknownPropsKey() throws Exception {
        var bad = mutate(root -> props(root, 4).put("foo", 1));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V3:") && e.contains("s05 props 含未知键 foo"));
    }

    @Test
    @DisplayName("step-card / derivation-popup 缺 stepRef → V3")
    void missingRequiredStepRef() throws Exception {
        var stepCard = mutate(root -> props(root, 4).remove("stepRef"));
        var popup = mutate(root -> props(root, 5).remove("stepRef"));

        assertThat(errorsOf(stepCard)).anyMatch(e -> e.startsWith("V3:") && e.contains("s05 缺 props 键 stepRef"));
        assertThat(errorsOf(popup)).anyMatch(e -> e.startsWith("V3:") && e.contains("s06 缺 props 键 stepRef"));
    }

    @Test
    @DisplayName("pitfallRef=3 越界（pitfalls 共 2 条）→ V3")
    void pitfallRefRange() throws Exception {
        var bad = mutate(root -> props(root, 11).put("pitfallRef", 3));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V3:") && e.contains("s12") && e.contains("pitfallRef=3"));
    }

    @Test
    @DisplayName("itemRef=4 越界（generalMethod 共 3 条）→ V3")
    void itemRefRange() throws Exception {
        var bad = mutate(root -> props(root, 14).put("itemRef", 4));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V3:") && e.contains("s15") && e.contains("itemRef=4"));
    }

    @Test
    @DisplayName("knowledgeRef=4 越界（knowledge 共 3 条）→ V3（contract.ts 超集）")
    void knowledgeRefRange() throws Exception {
        var bad = mutate(root -> props(root, 1).put("knowledgeRef", 4));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V3:") && e.contains("s02") && e.contains("knowledgeRef=4"));
    }

    @Test
    @DisplayName("pitfallRefs 含越界引用 / 空数组 → V3（contract.ts 超集）")
    void pitfallRefsInvalid() throws Exception {
        var beyond = mutate(root -> props(root, 13).set("pitfallRefs", MAPPER.createArrayNode().add(5)));
        var empty = mutate(root -> props(root, 13).set("pitfallRefs", MAPPER.createArrayNode()));

        assertThat(errorsOf(beyond)).anyMatch(e -> e.startsWith("V3:") && e.contains("s14") && e.contains("5"));
        assertThat(errorsOf(empty)).anyMatch(e -> e.startsWith("V3:") && e.contains("s14") && e.contains("非空数组"));
    }

    @Test
    @DisplayName("stepRef 类型非法（字符串）→ V3")
    void refWrongType() throws Exception {
        var bad = mutate(root -> props(root, 4).put("stepRef", "1"));

        assertThat(errorsOf(bad)).anyMatch(e -> e.startsWith("V3:") && e.contains("s05") && e.contains("类型非法"));
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

    private List<String> errorsOf(ContentJson content) throws Exception {
        var result = validator.validate(ctx(content));
        assertThat(result.pass()).as("变形应被打中，实际错误：%s", result.errors()).isFalse();
        return result.errors();
    }

    private static ObjectNode props(ObjectNode root, int index) {
        return (ObjectNode) ((ArrayNode) root.get("scenes")).get(index).get("props");
    }
}
