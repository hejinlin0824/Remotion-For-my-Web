package com.wyf.factory.validate;

import com.wyf.factory.content.ContentJson;
import com.wyf.factory.stations.Material;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * V1 结构校验：引擎 template/src/engine/contract.ts 的 Java 超集
 * （ACT_COMPONENTS 每幕组件白名单 / ttsText 非空 / popup formula 必填 /
 * problem-card 唯一首场）+ 条数硬校验 + 终审 §7 结构规则包。
 *
 * <p>每条规则一个私有方法，错误消息格式 {@code "V1/<规则名>: <具体差异>"}，
 * 供 T10 编排器回传 LLM 修正。audio_meta 一致性归 T8，此处不查。
 * 另含散文字段 LaTeX 禁令（T11 冒烟实证：裸 LaTeX 写进 statement 会被模板按纯文本渲染）。</p>
 */
@Component
public class V1Structural implements Validator {

    static final Set<String> PROBLEM_TYPES = Set.of("基础题", "计算题", "证明题", "应用题");
    static final Set<String> COMPONENTS = Set.of("problem-card", "knowledge-card", "step-card",
            "derivation-popup", "pitfall-card", "checklist-card", "general-list");
    /** 每幕允许的组件（contract.ts ACT_COMPONENTS）。 */
    private static final Map<Integer, Set<String>> ACT_COMPONENTS = Map.of(
            2, Set.of("problem-card", "knowledge-card"),
            3, Set.of("step-card", "derivation-popup", "pitfall-card", "checklist-card"),
            4, Set.of("general-list"));

    /**
     * 散文里的 LaTeX 标记：反斜杠命令序列（\le、\frac、\sqrt…）或 ^{、_{ 上下标。
     * 散文字段 = 纯中文叙述 + Unicode 简易符号（±√≤≥⇔）；T11 冒烟实证 GLM 会把
     * 裸 LaTeX 写进散文（"对 f(x)=x^{3}+ax^{2}+x 逐项求导"），模板按纯文本渲染成乱码。
     */
    private static final Pattern LATEX_IN_PROSE = Pattern.compile("\\\\[a-zA-Z]+|\\^\\{|_\\{");

    @Override
    public ValidationResult validate(ValidationContext ctx) {
        ContentJson content = ctx.content();
        List<ContentJson.Scene> scenes = content.scenes() == null ? List.of() : content.scenes();
        List<String> errors = new ArrayList<>();

        checkMeta(content, errors);
        checkCounts(content, errors);
        checkEachScene(scenes, errors);
        checkActOrder(scenes, errors);
        checkAct2First(scenes, errors);
        checkAct2Knowledge(scenes, errors);
        checkActCoverage(scenes, errors);
        checkPopupFollowsStepCard(scenes, errors);
        checkStepCardUnique(scenes, errors);
        checkStepRefSequence(scenes, content, errors);
        checkChecklistUnique(scenes, errors);
        checkItemRefSequence(scenes, content, errors);
        checkProseNoLatex(content, errors);

        return errors.isEmpty() ? ValidationResult.ok() : ValidationResult.fail(errors);
    }

    // ---- 规则1：meta ----
    private static void checkMeta(ContentJson content, List<String> errors) {
        var meta = content.meta();
        if (meta == null) {
            errors.add("V1/meta: 缺 meta");
            return;
        }
        if (!"16:9".equals(meta.aspect())) {
            errors.add("V1/meta: aspect='%s' 应为 16:9".formatted(meta.aspect()));
        }
        if (meta.problemType() == null || !PROBLEM_TYPES.contains(meta.problemType())) {
            errors.add("V1/meta: problemType='%s' 不在 {基础题,计算题,证明题,应用题}".formatted(meta.problemType()));
        }
    }

    // ---- 规则6：条数硬校验 ----
    private static void checkCounts(ContentJson content, List<String> errors) {
        range(content.knowledge(), "knowledge", 2, 4, errors);
        range(content.steps(), "steps", 3, 10, errors);
        range(content.pitfalls(), "pitfalls", 1, 3, errors);
        range(content.generalMethod(), "generalMethod", 3, 6, errors);
    }

    private static void range(List<?> list, String name, int min, int max, List<String> errors) {
        int size = list == null ? 0 : list.size();
        if (size < min || size > max) {
            errors.add("V1/条数: %s 条数 %d 超出范围 %d-%d".formatted(name, size, min, max));
        }
    }

    // ---- 规则2/3 + contract.ts 超集：act、组件白名单、每幕组件、ttsText、popup formula ----
    private static void checkEachScene(List<ContentJson.Scene> scenes, List<String> errors) {
        for (int i = 0; i < scenes.size(); i++) {
            ContentJson.Scene scene = scenes.get(i);
            String tag = tag(scene, i);
            if (scene.act() != 2 && scene.act() != 3 && scene.act() != 4) {
                errors.add("V1/act: %s act=%d 不在 {2,3,4}".formatted(tag, scene.act()));
            }
            String component = scene.component();
            if (component == null || !COMPONENTS.contains(component)) {
                errors.add("V1/组件白名单: %s 组件 '%s' 不在 7 组件白名单".formatted(tag, component));
                continue; // 组件未知时后续按组件的规则无从谈起
            }
            Set<String> allowedInAct = ACT_COMPONENTS.get(scene.act());
            if (allowedInAct != null && !allowedInAct.contains(component)) {
                errors.add("V1/act组件: %s 组件 '%s' 不允许出现在 act%d".formatted(tag, component, scene.act()));
            }
            if (scene.ttsText() == null || scene.ttsText().isBlank()) {
                errors.add("V1/ttsText: %s ttsText 为空".formatted(tag));
            }
            if ("derivation-popup".equals(component) && blank(props(scene).get("formula"))) {
                errors.add("V1/popupFormula: %s derivation-popup 缺 formula".formatted(tag));
            }
        }
    }

    // ---- 规则8：幕内场景顺序 act 非降（2 全在 3 前、3 全在 4 前） ----
    private static void checkActOrder(List<ContentJson.Scene> scenes, List<String> errors) {
        Integer prev = null;
        for (int i = 0; i < scenes.size(); i++) {
            ContentJson.Scene scene = scenes.get(i);
            if (prev != null && scene.act() < prev) {
                errors.add("V1/幕顺序: %s act=%d 出现在 act=%d 场景之后".formatted(tag(scene, i), scene.act(), prev));
            }
            prev = scene.act();
        }
    }

    // ---- 规则4a + contract.ts 超集：problem-card 只能是首场 ----
    private static void checkAct2First(List<ContentJson.Scene> scenes, List<String> errors) {
        if (scenes.isEmpty()) {
            return;
        }
        ContentJson.Scene first = scenes.get(0);
        if (!"problem-card".equals(first.component())) {
            errors.add("V1/act2首场: 首场 %s 组件 '%s' 应为 problem-card".formatted(tag(first, 0), first.component()));
        }
        for (int i = 1; i < scenes.size(); i++) {
            ContentJson.Scene scene = scenes.get(i);
            if ("problem-card".equals(scene.component())) {
                errors.add("V1/act2首场: problem-card 只能是首场，%s 违规".formatted(tag(scene, i)));
            }
        }
    }

    // ---- 规则4b：act2 至少 1 个 knowledge-card ----
    private static void checkAct2Knowledge(List<ContentJson.Scene> scenes, List<String> errors) {
        boolean any = scenes.stream().anyMatch(s -> s.act() == 2 && "knowledge-card".equals(s.component()));
        if (!any) {
            errors.add("V1/act2知识点: act2 缺 knowledge-card（至少 1 场）");
        }
    }

    // ---- 规则5：act3、act4 各 ≥1 场 ----
    private static void checkActCoverage(List<ContentJson.Scene> scenes, List<String> errors) {
        if (scenes.stream().noneMatch(s -> s.act() == 3)) {
            errors.add("V1/幕覆盖: 缺 act3 场景（至少 1 场）");
        }
        if (scenes.stream().noneMatch(s -> s.act() == 4)) {
            errors.add("V1/幕覆盖: 缺 act4 场景（至少 1 场）");
        }
    }

    // ---- 规则7a：popup 必须紧跟同 stepRef 的 step-card（前置即违规） ----
    private static void checkPopupFollowsStepCard(List<ContentJson.Scene> scenes, List<String> errors) {
        for (int i = 0; i < scenes.size(); i++) {
            ContentJson.Scene scene = scenes.get(i);
            if (!"derivation-popup".equals(scene.component())) {
                continue;
            }
            ContentJson.Scene prev = i > 0 ? scenes.get(i - 1) : null;
            Long ref = ref(props(scene).get("stepRef"));
            boolean follows = prev != null && "step-card".equals(prev.component())
                    && ref != null && ref.equals(ref(props(prev).get("stepRef")));
            if (!follows) {
                errors.add("V1/popup紧跟: %s derivation-popup 未紧跟同 stepRef 的 step-card".formatted(tag(scene, i)));
            }
        }
    }

    // ---- 规则7b：同 stepRef 的 step-card 至多一张 ----
    private static void checkStepCardUnique(List<ContentJson.Scene> scenes, List<String> errors) {
        Map<Long, Integer> counts = new LinkedHashMap<>();
        for (ContentJson.Scene scene : scenes) {
            if ("step-card".equals(scene.component())) {
                Long ref = ref(props(scene).get("stepRef"));
                if (ref != null) {
                    counts.merge(ref, 1, Integer::sum);
                }
            }
        }
        counts.forEach((ref, count) -> {
            if (count > 1) {
                errors.add("V1/step-card唯一: stepRef=%d 的 step-card 出现 %d 张（至多 1 张）".formatted(ref, count));
            }
        });
    }

    // ---- 规则7c：step-card 引用的 stepRef 序列 = 1..steps.length 严格递增全引用 ----
    private static void checkStepRefSequence(List<ContentJson.Scene> scenes, ContentJson content,
                                             List<String> errors) {
        List<Long> sequence = new ArrayList<>();
        for (ContentJson.Scene scene : scenes) {
            if ("step-card".equals(scene.component())) {
                Long ref = ref(props(scene).get("stepRef"));
                if (ref != null) {
                    sequence.add(ref);
                }
            }
        }
        int expectedSize = content.steps() == null ? 0 : content.steps().size();
        if (!sequence.equals(fullRange(expectedSize))) {
            errors.add("V1/stepRef序列: step-card stepRef 序列 %s 应为 1..%d 严格递增全引用"
                    .formatted(sequence, expectedSize));
        }
    }

    // ---- 规则7d：checklist-card 至多 1 场 ----
    private static void checkChecklistUnique(List<ContentJson.Scene> scenes, List<String> errors) {
        long count = scenes.stream().filter(s -> "checklist-card".equals(s.component())).count();
        if (count > 1) {
            errors.add("V1/checklist唯一: checklist-card 出现 %d 场（至多 1 场）".formatted(count));
        }
    }

    // ---- 规则7e：act4 的 itemRef 序列 = 1..generalMethod.length 连续递增 ----
    private static void checkItemRefSequence(List<ContentJson.Scene> scenes, ContentJson content,
                                             List<String> errors) {
        List<Long> sequence = new ArrayList<>();
        for (ContentJson.Scene scene : scenes) {
            if (scene.act() == 4 && "general-list".equals(scene.component())) {
                Long ref = ref(props(scene).get("itemRef"));
                if (ref != null) {
                    sequence.add(ref);
                }
            }
        }
        int expectedSize = content.generalMethod() == null ? 0 : content.generalMethod().size();
        if (!sequence.equals(fullRange(expectedSize))) {
            errors.add("V1/itemRef序列: act4 itemRef 序列 %s 应为 1..%d 连续递增".formatted(sequence, expectedSize));
        }
    }

    // ---- 规则9：散文字段 LaTeX 禁令（LaTeX 只允许 math 段 / steps.derivation / knowledge.formula / popup formula） ----
    private static void checkProseNoLatex(ContentJson content, List<String> errors) {
        List<ContentJson.Line> lines = content.problem() == null || content.problem().lines() == null
                ? List.of() : content.problem().lines();
        for (int i = 0; i < lines.size(); i++) {
            List<ContentJson.Seg> segments = lines.get(i).segments() == null
                    ? List.of() : lines.get(i).segments();
            for (int j = 0; j < segments.size(); j++) {
                ContentJson.Seg seg = segments.get(j);
                if ("text".equals(seg.type())) {
                    checkProse("problem.lines[%d].segments[%d].value".formatted(i, j), seg.value(), errors);
                }
            }
        }
        List<Material.Knowledge> knowledge = content.knowledge() == null ? List.of() : content.knowledge();
        for (int i = 0; i < knowledge.size(); i++) {
            Material.Knowledge item = knowledge.get(i);
            checkProse("knowledge[%d].claim".formatted(i), item.claim(), errors);
            checkProse("knowledge[%d].premise".formatted(i), item.premise(), errors);
            checkProse("knowledge[%d].trap".formatted(i), item.trap(), errors);
        }
        List<Material.Step> steps = content.steps() == null ? List.of() : content.steps();
        for (int i = 0; i < steps.size(); i++) {
            Material.Step step = steps.get(i);
            checkProse("steps[%d].statement".formatted(i), step.statement(), errors);
            checkProse("steps[%d].note".formatted(i), step.note(), errors);
        }
        List<Material.Pitfall> pitfalls = content.pitfalls() == null ? List.of() : content.pitfalls();
        for (int i = 0; i < pitfalls.size(); i++) {
            Material.Pitfall pitfall = pitfalls.get(i);
            checkProse("pitfalls[%d].claim".formatted(i), pitfall.claim(), errors);
            checkProse("pitfalls[%d].why".formatted(i), pitfall.why(), errors);
        }
        List<Material.MethodItem> generalMethod = content.generalMethod() == null
                ? List.of() : content.generalMethod();
        for (int i = 0; i < generalMethod.size(); i++) {
            Material.MethodItem item = generalMethod.get(i);
            checkProse("generalMethod[%d].step".formatted(i), item.step(), errors);
            checkProse("generalMethod[%d].trick".formatted(i), item.trick(), errors);
        }
        for (int i = 0; i < scenes(content).size(); i++) {
            ContentJson.Scene scene = scenes(content).get(i);
            checkProse("scenes[%d].ttsText".formatted(i), scene.ttsText(), errors);
            Map<String, Object> sceneProps = scene.props() == null ? Map.of() : scene.props();
            for (Map.Entry<String, Object> entry : sceneProps.entrySet()) {
                if ("formula".equals(entry.getKey())) {
                    continue; // derivation-popup 的 KaTeX 公式，属合法 LaTeX 字段
                }
                if (entry.getValue() instanceof String value) {
                    checkProse("scenes[%d].props.%s".formatted(i, entry.getKey()), value, errors);
                }
            }
        }
    }

    private static void checkProse(String path, String value, List<String> errors) {
        if (value == null) {
            return;
        }
        var marker = LATEX_IN_PROSE.matcher(value);
        if (marker.find()) {
            errors.add("V1/散文LaTeX: %s 含 LaTeX 标记「%s」，散文字段只允许中文/Unicode 简易符号，"
                    .formatted(path, marker.group())
                    + "公式只可进 derivation、knowledge.formula 或 math 段");
        }
    }

    // ---- helpers ----

    private static List<ContentJson.Scene> scenes(ContentJson content) {
        return content.scenes() == null ? List.of() : content.scenes();
    }

    private static List<Long> fullRange(int size) {
        List<Long> range = new ArrayList<>();
        for (long i = 1; i <= size; i++) {
            range.add(i);
        }
        return range;
    }

    /** 正整数引用（JSON number → 整数），非法返回 null（缺键/类型错由 V3 报）。 */
    private static Long ref(Object value) {
        if (value instanceof Number number) {
            double d = number.doubleValue();
            if (d >= 1 && d == Math.rint(d)) {
                return (long) d;
            }
        }
        return null;
    }

    private static Map<String, Object> props(ContentJson.Scene scene) {
        return scene.props() == null ? Map.of() : scene.props();
    }

    private static boolean blank(Object value) {
        return !(value instanceof String s) || s.isBlank();
    }

    private static String tag(ContentJson.Scene scene, int index) {
        return scene.id() == null ? "scenes[" + index + "]" : scene.id();
    }
}
