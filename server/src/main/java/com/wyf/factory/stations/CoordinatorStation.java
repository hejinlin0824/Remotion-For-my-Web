package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.GlmException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GEN-P0 协调者工位（T18 分片生成第 0 片）：ExtractResult → {@link Skeleton} 骨架。
 *
 * <p>协调者看得到全题，单次小输出统一裁决：条数计划、每步锚点指派、全部场景清单、
 * 术语表——下游分片只按骨架填内容，锚点误用/条数漂移/场景 id 冲突在此单源化。</p>
 *
 * <p>user 载荷 = 题干 JSON（ExtractResult 序列化）；errors 重载把上一轮失败清单逐条
 * （"- " 前缀）追加在载荷尾部（{@link StationChecks#retrySuffix}，通道与 T19a 前一致）。</p>
 *
 * <p>骨架校验（违反抛 retryable {@link GlmException}，差异清单即回传重试清单）：</p>
 * <ul>
 *   <li>problemType ∈ {基础题,计算题,证明题,应用题}</li>
 *   <li>counts 四段条数在题型骨架规格（{@link SkeletonLibrary}）范围内；problemType 非法/缺失
 *       时回落 StationChecks 全局常量（=计算题规格），题型门已记 problems 不二次记错（T20b）</li>
 *   <li>anchors 与 counts.steps 等长且每项指向真实存在的题干行 id（锚点行存在性）</li>
 *   <li>scenes：id 唯一、act∈{2,3,4}、组件在每幕白名单内、act 序非降、首场 problem-card、
 *       act2 至少一场 knowledge-card、act3/act4 各至少一场</li>
 *   <li>scenes 计划级 stepRef 不变量（T18.1，违规零分片成本打回 P0 重排）：
 *       R1 归属——step-card/derivation-popup 必带 1..counts.steps 整数、其余组件必不带；
 *       R2 序列——step-card 的 stepRef 按 plan 顺序恰为 1..S（覆盖恰一次且有序，
 *       计划级一次拦死 V1 规则7b/7c 同族结构病）；R3 popup 紧跟——derivation-popup
 *       的前一项必须是同 stepRef 的 step-card（镜像 V1Structural 规则7a）。
 *       R4（同 stepRef popup 至多一个）由 R3 隐式保证：第二个 popup 的前一项是 popup，必违 R3。</li>
 *   <li>glossary：非空，每条 term/standard 为字符串</li>
 * </ul>
 */
@Component
public class CoordinatorStation {

    /** 坏输出进异常消息的原始响应截断长度 */
    private static final int RAW_SNIPPET = 500;

    private final GlmClient glm;
    private final ObjectMapper mapper = new ObjectMapper();

    public CoordinatorStation(GlmClient glm) {
        this.glm = glm;
    }

    /** 题干 → 骨架。 */
    public Skeleton generate(ExtractResult extract) {
        return generate(extract, List.of());
    }

    /** 错误清单回传重试：user 载荷尾部追加「上一轮校验失败清单（必须全部修正）」。 */
    public Skeleton generate(ExtractResult extract, List<String> errors) {
        return parse(glm.chat(Prompts.COORDINATOR, payload(extract) + StationChecks.retrySuffix(errors)), extract);
    }

    private String payload(ExtractResult extract) {
        try {
            return mapper.writeValueAsString(extract);
        } catch (IOException e) {
            throw new GlmException("题干序列化失败", false, e);
        }
    }

    private Skeleton parse(String raw, ExtractResult extract) {
        JsonNode root;
        try {
            root = mapper.readTree(StationChecks.stripCodeFence(raw));
        } catch (IOException e) {
            throw new GlmException("骨架输出不是 JSON：原始响应=" + StationChecks.snippet(raw, RAW_SNIPPET), true, e);
        }
        List<String> problems = new ArrayList<>();
        validate(root, extract, problems);
        if (!problems.isEmpty()) {
            throw new GlmException(problems, true);
        }
        try {
            return mapper.treeToValue(root, Skeleton.class);
        } catch (IOException e) {
            throw new GlmException("骨架输出绑定失败：" + e.getMessage(), true, e);
        }
    }

    /** 骨架校验：全部规则逐条记差异（一条不漏，回传清单一次性纠全）。 */
    private static void validate(JsonNode root, ExtractResult extract, List<String> problems) {
        JsonNode problemType = root.path("problemType");
        String typeName = problemType.isTextual() ? problemType.asText() : "";
        if (!StationChecks.PROBLEM_TYPES.contains(typeName)) {
            problems.add("骨架 problemType='%s' 不在 {基础题,计算题,证明题,应用题}".formatted(typeName));
        }

        // T20b 题型骨架规格：合法题型查 SkeletonLibrary，非法/缺失回落全局常量（=计算题规格）。
        // 题型门在上面已记 problems，此处不二次记错（镜像 T18.1 counts.steps 非法防级联范式）。
        SkeletonLibrary.Spec spec = SkeletonLibrary.spec(typeName.isEmpty() ? null : typeName);
        String typeLabel = typeName.isEmpty() ? "-" : typeName;
        JsonNode counts = root.path("counts");
        checkCount(counts, typeLabel, "knowledge", spec.knowledge(), problems);
        checkCount(counts, typeLabel, "steps", spec.steps(), problems);
        checkCount(counts, typeLabel, "pitfalls", spec.pitfalls(), problems);
        checkCount(counts, typeLabel, "generalMethod", spec.generalMethod(), problems);

        checkAnchors(root.path("anchors"), counts, extract, problems);
        checkScenes(root.path("scenes"), counts, problems);

        JsonNode glossary = root.path("glossary");
        if (!glossary.isArray() || glossary.isEmpty()) {
            problems.add("骨架 glossary 缺失或不是非空数组");
        } else {
            for (int i = 0; i < glossary.size(); i++) {
                JsonNode term = glossary.get(i);
                if (!term.path("term").isTextual() || !term.path("standard").isTextual()) {
                    problems.add("骨架 glossary[" + i + "] 缺必需字段 term/standard");
                }
            }
        }
    }

    /** 条数校验（T20b 按题型规格）：越界消息含题型令牌与上下限（如「骨架（证明题）steps=3 低于下限 4」）。 */
    private static void checkCount(JsonNode counts, String typeLabel, String name, SkeletonLibrary.Range range,
                                   List<String> problems) {
        JsonNode value = counts.path(name);
        if (!value.isInt()) {
            problems.add("骨架 counts." + name + " 缺失或不是整数");
        } else if (value.asInt() < range.min()) {
            problems.add("骨架（" + typeLabel + "）" + name + "=" + value.asInt() + " 低于下限 " + range.min());
        } else if (value.asInt() > range.max()) {
            problems.add("骨架（" + typeLabel + "）" + name + "=" + value.asInt() + " 高于上限 " + range.max());
        }
    }

    /** 锚点指派校验：与 steps 等长、每项是真实存在的题干行 id（核心：协调者单源指派）。 */
    private static void checkAnchors(JsonNode anchors, JsonNode counts, ExtractResult extract,
                                     List<String> problems) {
        if (!anchors.isArray() || anchors.isEmpty()) {
            problems.add("骨架 anchors 缺失或不是非空数组");
            return;
        }
        Set<String> lineIds = new HashSet<>();
        for (ExtractResult.Line line : extract.lines()) {
            lineIds.add(line.id());
        }
        int steps = counts.path("steps").isInt() ? counts.path("steps").asInt() : -1;
        if (steps >= 0 && anchors.size() != steps) {
            problems.add("骨架 anchors 条数 " + anchors.size() + " 与 counts.steps=" + steps + " 不等长");
        }
        for (int i = 0; i < anchors.size(); i++) {
            JsonNode anchor = anchors.get(i);
            if (!anchor.isTextual()) {
                problems.add("骨架 anchors[" + i + "] 不是字符串");
            } else if (!lineIds.contains(anchor.asText())) {
                problems.add("骨架 anchors[" + i + "]='" + anchor.asText() + "' 不存在于题干行 id（锚点行存在性）");
            }
        }
    }

    /**
     * 场景清单校验：id 唯一/act 白名单/每幕组件白名单/act 序非降/首场 problem-card/幕覆盖
     * + 计划级 stepRef 不变量（T18.1：R1 归属、R2 step-card 序列恰为 1..steps、R3 popup 紧跟）。
     * counts.steps 非法（缺/非整数）时 R1 范围检查与 R2 跳过（problems 已有对应条目，不级联误报）。
     */
    private static void checkScenes(JsonNode scenes, JsonNode counts, List<String> problems) {
        if (!scenes.isArray() || scenes.isEmpty()) {
            problems.add("骨架 scenes 缺失或不是非空数组");
            return;
        }
        int steps = counts.path("steps").isInt() ? counts.path("steps").asInt() : -1;
        Set<String> ids = new HashSet<>();
        Integer prevAct = null;
        String prevComponent = null;
        Integer prevStepRef = null;
        boolean act2Knowledge = false;
        boolean act3Seen = false;
        boolean act4Seen = false;
        List<Integer> stepCardRefs = new ArrayList<>();
        boolean allStepCardRefsValid = true;
        for (int i = 0; i < scenes.size(); i++) {
            JsonNode scene = scenes.get(i);
            if (!scene.isObject()) {
                problems.add("骨架 scenes[" + i + "] 不是对象");
                prevComponent = null;
                prevStepRef = null;
                continue;
            }
            JsonNode id = scene.path("id");
            JsonNode act = scene.path("act");
            JsonNode component = scene.path("component");
            JsonNode stepRef = scene.path("stepRef");
            String tag = id.isTextual() ? id.asText() : "-";
            if (!id.isTextual()) {
                problems.add("骨架 scenes[" + i + "] 缺必需字段 id");
            } else if (!ids.add(id.asText())) {
                problems.add("骨架 scenes 场景 id 重复：" + id.asText());
            }
            if (!act.isInt() || !Set.of(2, 3, 4).contains(act.asInt())) {
                problems.add("骨架 scenes[" + i + "] act 不在 {2,3,4}");
                prevComponent = null;
                prevStepRef = null;
                continue;
            }
            int actValue = act.asInt();
            if (prevAct != null && actValue < prevAct) {
                problems.add("骨架 scenes[" + i + "] act=" + actValue + " 出现在 act=" + prevAct + " 场景之后（act 序非降）");
            }
            prevAct = actValue;
            Set<String> allowed = StationChecks.ACT_COMPONENTS.get(actValue);
            boolean componentValid = component.isTextual() && StationChecks.COMPONENTS.contains(component.asText());
            if (!componentValid) {
                problems.add("骨架 scenes[" + i + "] 组件 '" + (component.isTextual() ? component.asText() : "")
                        + "' 不在 7 组件白名单");
            } else if (!allowed.contains(component.asText())) {
                problems.add("骨架 scenes[" + i + "] 组件 '" + component.asText() + "' 不允许出现在 act" + actValue);
            }
            if (componentValid) {
                // R1 stepRef 归属：step-card/derivation-popup 必带 1..steps 整数，其余组件必不带
                boolean refOk = checkStepRefOwnership(i, tag, component.asText(), stepRef, steps,
                        stepCardRefs, problems);
                if ("step-card".equals(component.asText())) {
                    allStepCardRefsValid &= refOk;
                }
                // R3 popup 紧跟（镜像 V1Structural 规则7a）：popup 前一项必须是同 stepRef 的 step-card
                if ("derivation-popup".equals(component.asText()) && refOk) {
                    boolean follows = "step-card".equals(prevComponent) && prevStepRef != null
                            && prevStepRef == stepRef.asInt();
                    if (!follows) {
                        problems.add("骨架 scenes[" + i + "](" + tag + ") derivation-popup 未紧跟同 stepRef 的 step-card"
                                + "（计划级 popup 紧跟，场景片改不动）");
                    }
                }
            }
            if (i == 0 && !"problem-card".equals(component.asText())) {
                problems.add("骨架 scenes[0] 组件应为 problem-card（首场）");
            }
            if (actValue == 2 && "knowledge-card".equals(component.asText())) {
                act2Knowledge = true;
            }
            act3Seen |= actValue == 3;
            act4Seen |= actValue == 4;
            prevComponent = component.isTextual() ? component.asText() : null;
            prevStepRef = stepRef.isInt() ? stepRef.asInt() : null;
        }
        // R2 step-card 覆盖恰一次且有序：stepRef 序列（按 plan 顺序）必须恰为 1..steps
        // （缺步/重步/乱序计划级一次拦死，等价 V1 规则7b/7c）。有 step-card 缺/坏 stepRef
        // （R1 已记）或 counts.steps 非法（checkCount 已记）时跳过，不级联误报。
        if (steps >= 1 && allStepCardRefsValid) {
            List<Integer> expected = new ArrayList<>();
            for (int s = 1; s <= steps; s++) {
                expected.add(s);
            }
            if (!stepCardRefs.equals(expected)) {
                problems.add("骨架 scenes step-card 的 stepRef 序列 " + stepCardRefs + " 应为 1.." + steps
                        + "（缺步/重步/乱序均不允许，计划级一次拦死）");
            }
        }
        if (!act2Knowledge) {
            problems.add("骨架 scenes act2 缺 knowledge-card（至少 1 场）");
        }
        if (!act3Seen) {
            problems.add("骨架 scenes 缺 act3 场景（至少 1 场）");
        }
        if (!act4Seen) {
            problems.add("骨架 scenes 缺 act4 场景（至少 1 场）");
        }
    }

    /**
     * R1 stepRef 归属单场校验：返回该场 stepRef 是否合法（1..steps 内的有效整数）。
     * step-card 的合法值顺带收进 {@code stepCardRefs}（R2 序列核对的输入）；
     * counts.steps 非法（steps&lt;1）时只查存在性不查范围（checkCount 已记，不级联）。
     */
    private static boolean checkStepRefOwnership(int i, String tag, String componentName, JsonNode stepRef,
                                                 int steps, List<Integer> stepCardRefs, List<String> problems) {
        boolean stepBearing = "step-card".equals(componentName) || "derivation-popup".equals(componentName);
        if (stepBearing) {
            if (!stepRef.isInt()) {
                problems.add("骨架 scenes[" + i + "](" + tag + ") " + componentName
                        + " 缺 stepRef 或不是整数（计划级步骤分派必需）");
                return false;
            }
            if (steps >= 1 && (stepRef.asInt() < 1 || stepRef.asInt() > steps)) {
                problems.add("骨架 scenes[" + i + "](" + tag + ") " + componentName
                        + " stepRef=" + stepRef.asInt() + " 超出 1.." + steps + "（计划级步骤分派范围）");
                return false;
            }
            if ("step-card".equals(componentName)) {
                stepCardRefs.add(stepRef.asInt());
            }
            return true;
        }
        if (!stepRef.isMissingNode()) {
            problems.add("骨架 scenes[" + i + "](" + tag + ") " + componentName
                    + " 不得携带 stepRef（仅 step-card/derivation-popup 分派步骤）");
        }
        return false;
    }
}
