package com.wyf.factory.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ContentJson 绑定契约：以封版模板 golden（template/src/data/content.json，只读）为准——
 * 字段名逐字一致、toJson 紧凑、绑定→序列化→再绑定往返稳定。
 */
class ContentJsonTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** golden content.json 绝对路径（封版模板只读原件）。 */
    private static String golden() throws Exception {
        Path path = Path.of("..", "template", "src", "data", "content.json").toAbsolutePath().normalize();
        assertThat(Files.isRegularFile(path)).as("golden content.json 存在：%s", path).isTrue();
        return Files.readString(path);
    }

    @Test
    @DisplayName("golden 绑定→toJson→再绑定：与首次绑定对象 equals（往返稳定）")
    void golden_roundTripStable() throws Exception {
        ContentJson first = MAPPER.readValue(golden(), ContentJson.class);
        ContentJson second = MAPPER.readValue(first.toJson(), ContentJson.class);

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("toJson 紧凑（无换行缩进）且键集合与 golden 逐层完全一致")
    void golden_keySetsIdenticalAndCompact() throws Exception {
        ContentJson bound = MAPPER.readValue(golden(), ContentJson.class);

        String json = bound.toJson();
        assertThat(json).doesNotContain("\n").doesNotContain("\r");

        assertSameShape(MAPPER.readTree(golden()), MAPPER.readTree(json), "$");
    }

    @Test
    @DisplayName("golden 字段值映射：meta/knowledge/steps/pitfalls/generalMethod/scenes 条数与取值路径")
    void golden_fieldMapping() throws Exception {
        ContentJson bound = MAPPER.readValue(golden(), ContentJson.class);

        assertThat(bound.meta().aspect()).isEqualTo("16:9");
        assertThat(bound.meta().problemType()).isEqualTo("计算题");
        assertThat(bound.problem().lines()).hasSize(3);
        assertThat(bound.problem().lines().get(0).id()).isEqualTo("L1");
        assertThat(bound.problem().lines().get(1).segments().get(1).value()).isEqualTo("f(x)");
        assertThat(bound.problem().lines().get(1).segments().get(3).value()).isEqualTo("\\mathbb{R}");
        assertThat(bound.knowledge()).hasSize(3);
        assertThat(bound.steps()).hasSize(5);
        assertThat(bound.pitfalls()).hasSize(2);
        assertThat(bound.generalMethod()).hasSize(3);
        assertThat(bound.scenes()).hasSize(17);

        ContentJson.Scene first = bound.scenes().get(0);
        assertThat(first.id()).isEqualTo("s01");
        assertThat(first.act()).isEqualTo(2);
        assertThat(first.component()).isEqualTo("problem-card");
        assertThat(first.props()).isEmpty();

        ContentJson.Scene popup = bound.scenes().get(5);
        assertThat(popup.component()).isEqualTo("derivation-popup");
        assertThat(popup.props()).isEqualTo(Map.of("stepRef", 1, "formula", "f'(x)=3x^{2}+2ax+1"));

        ContentJson.Scene checklist = bound.scenes().get(13);
        assertThat(checklist.props().get("pitfallRefs")).isEqualTo(java.util.List.of(1, 2));
    }

    /** 递归断言两棵 JSON 树的键集合逐层一致（数组按下标配对）。 */
    private static void assertSameShape(JsonNode expected, JsonNode actual, String path) {
        if (expected.isObject()) {
            assertThat(actual.isObject()).as("%s 应为对象", path).isTrue();
            Set<String> expectedKeys = new TreeSet<>();
            expected.fieldNames().forEachRemaining(expectedKeys::add);
            Set<String> actualKeys = new TreeSet<>();
            actual.fieldNames().forEachRemaining(actualKeys::add);
            assertThat(actualKeys).as("%s 键集合", path)
                    .containsExactlyInAnyOrderElementsOf(expectedKeys);
            expectedKeys.forEach(key -> assertSameShape(expected.get(key), actual.get(key), path + "." + key));
        } else if (expected.isArray()) {
            assertThat(actual.isArray()).as("%s 应为数组", path).isTrue();
            assertThat(actual.size()).as("%s 长度", path).isEqualTo(expected.size());
            for (int i = 0; i < expected.size(); i++) {
                assertSameShape(expected.get(i), actual.get(i), path + "[" + i + "]");
            }
        }
    }
}
