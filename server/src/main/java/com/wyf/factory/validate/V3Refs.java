package com.wyf.factory.validate;

import com.wyf.factory.content.ContentJson;
import com.wyf.factory.stations.Material;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * V3 引用合法：scenes[].props 的 stepRef/pitfallRef/itemRef/knowledgeRef ∈ 1..对应素材条数、
 * usesAnchor（含 steps[].usesAnchor）∈ problem.lines[].id、未知 props 键警告性 error、
 * 每组件必需 props 键存在。范围表与 contract.ts 一致并补齐 knowledge-card/knowledgeRef、
 * checklist-card/pitfallRefs、general-list/itemRef 的必需键。
 */
@Component
public class V3Refs implements Validator {

    /** props 已知键全集（未知键 → 警告性 error，回传 LLM 让其自检）。 */
    private static final Set<String> KNOWN_PROPS = Set.of(
            "knowledgeRef", "stepRef", "formula", "pitfallRef", "pitfallRefs", "itemRef", "usesAnchor");

    /** 组件 → 必需 props 键。 */
    private static final Map<String, List<String>> REQUIRED_PROPS = Map.of(
            "knowledge-card", List.of("knowledgeRef"),
            "step-card", List.of("stepRef"),
            "derivation-popup", List.of("stepRef"),
            "pitfall-card", List.of("pitfallRef"),
            "checklist-card", List.of("pitfallRefs"),
            "general-list", List.of("itemRef"));

    /** 引用键 → 被引用素材名（错误消息用）。 */
    private static final Map<String, String> REF_TARGET = Map.of(
            "knowledgeRef", "knowledge",
            "stepRef", "steps",
            "pitfallRef", "pitfalls",
            "itemRef", "generalMethod");

    @Override
    public ValidationResult validate(ValidationContext ctx) {
        ContentJson content = ctx.content();
        List<ContentJson.Scene> scenes = content.scenes() == null ? List.of() : content.scenes();
        int knowledge = size(content.knowledge());
        int steps = size(content.steps());
        int pitfalls = size(content.pitfalls());
        int generalMethod = size(content.generalMethod());
        Set<String> lineIds = lineIds(content);
        List<String> errors = new ArrayList<>();

        for (int i = 0; i < scenes.size(); i++) {
            ContentJson.Scene scene = scenes.get(i);
            String tag = scene.id() == null ? "scenes[" + i + "]" : scene.id();
            Map<String, Object> props = scene.props() == null ? Map.of() : scene.props();

            props.keySet().stream()
                    .filter(key -> !KNOWN_PROPS.contains(key))
                    .forEach(key -> errors.add("V3: %s props 含未知键 %s".formatted(tag, key)));

            for (String required : REQUIRED_PROPS.getOrDefault(scene.component(), List.of())) {
                if (props.get(required) == null) {
                    errors.add("V3: %s 缺 props 键 %s".formatted(tag, required));
                }
            }

            for (Map.Entry<String, Integer> entry : Map.of(
                    "knowledgeRef", knowledge,
                    "stepRef", steps,
                    "pitfallRef", pitfalls,
                    "itemRef", generalMethod).entrySet()) {
                if (props.containsKey(entry.getKey())) {
                    checkRef(tag, entry.getKey(), props.get(entry.getKey()), entry.getValue(), errors);
                }
            }
            if (props.containsKey("usesAnchor")) {
                checkAnchor(tag, props.get("usesAnchor"), lineIds, errors);
            }
            checkPitfallRefs(tag, props, pitfalls, errors);
        }

        List<Material.Step> stepList = content.steps() == null ? List.of() : content.steps();
        for (int i = 0; i < stepList.size(); i++) {
            String anchor = stepList.get(i).usesAnchor();
            if (anchor == null || !lineIds.contains(anchor)) {
                errors.add("V3: steps[%d].usesAnchor='%s' 不存在于 problem.lines".formatted(i, anchor));
            }
        }

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    private static void checkRef(String tag, String key, Object value, int size, List<String> errors) {
        Long ref = ref(value);
        if (ref == null) {
            errors.add("V3: %s %s 类型非法（需正整数，实际 %s）".formatted(tag, key, value));
        } else if (ref < 1 || ref > size) {
            errors.add("V3: %s %s=%d 越界（%s 共 %d 条）".formatted(tag, key, ref, REF_TARGET.get(key), size));
        }
    }

    private static void checkAnchor(String tag, Object value, Set<String> lineIds, List<String> errors) {
        if (!(value instanceof String anchor) || !lineIds.contains(anchor)) {
            errors.add("V3: %s usesAnchor='%s' 不存在于 problem.lines".formatted(tag, value));
        }
    }

    private static void checkPitfallRefs(String tag, Map<String, Object> props, int pitfalls, List<String> errors) {
        Object value = props.get("pitfallRefs");
        if (value == null) {
            return; // 缺键已按必需键报过
        }
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            errors.add("V3: %s pitfallRefs 非法（需非空数组）".formatted(tag));
            return;
        }
        for (Object item : list) {
            Long ref = ref(item);
            if (ref == null || ref < 1 || ref > pitfalls) {
                errors.add("V3: %s pitfallRefs 含非法引用 %s（pitfalls 共 %d 条）".formatted(tag, item, pitfalls));
            }
        }
    }

    // ---- helpers ----

    private static int size(List<?> list) {
        return list == null ? 0 : list.size();
    }

    private static Set<String> lineIds(ContentJson content) {
        List<ContentJson.Line> lines = content.problem() == null || content.problem().lines() == null
                ? List.of() : content.problem().lines();
        Set<String> ids = new java.util.HashSet<>();
        for (ContentJson.Line line : lines) {
            if (line.id() != null) {
                ids.add(line.id());
            }
        }
        return ids;
    }

    /** 整数引用（JSON number → 整数）；非数值/非整数值返回 null。正负交给范围校验。 */
    private static Long ref(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (d == Math.rint(d) && Math.abs(d) <= 9.0E15) {
                return (long) d;
            }
        }
        return null;
    }
}
