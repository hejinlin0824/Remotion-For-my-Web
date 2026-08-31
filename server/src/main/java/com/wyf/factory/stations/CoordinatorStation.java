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
 *   <li>counts 四段条数在 StationChecks 既有 MIN/MAX 范围内</li>
 *   <li>anchors 与 counts.steps 等长且每项指向真实存在的题干行 id（锚点行存在性）</li>
 *   <li>scenes：id 唯一、act∈{2,3,4}、组件在每幕白名单内、act 序非降、首场 problem-card、
 *       act2 至少一场 knowledge-card、act3/act4 各至少一场</li>
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
        if (!problemType.isTextual() || !StationChecks.PROBLEM_TYPES.contains(problemType.asText())) {
            problems.add("骨架 problemType='%s' 不在 {基础题,计算题,证明题,应用题}"
                    .formatted(problemType.isTextual() ? problemType.asText() : ""));
        }

        JsonNode counts = root.path("counts");
        checkCount(counts, "knowledge", StationChecks.KNOWLEDGE_MIN, StationChecks.KNOWLEDGE_MAX, problems);
        checkCount(counts, "steps", StationChecks.STEPS_MIN, StationChecks.STEPS_MAX, problems);
        checkCount(counts, "pitfalls", StationChecks.PITFALLS_MIN, StationChecks.PITFALLS_MAX, problems);
        checkCount(counts, "generalMethod", StationChecks.GENERAL_METHOD_MIN, StationChecks.GENERAL_METHOD_MAX, problems);

        checkAnchors(root.path("anchors"), counts, extract, problems);
        checkScenes(root.path("scenes"), problems);

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

    private static void checkCount(JsonNode counts, String name, int min, int max, List<String> problems) {
        JsonNode value = counts.path(name);
        if (!value.isInt()) {
            problems.add("骨架 counts." + name + " 缺失或不是整数");
        } else if (value.asInt() < min || value.asInt() > max) {
            problems.add("骨架 counts." + name + " 条数 " + value.asInt() + " 超出范围 " + min + "-" + max);
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

    /** 场景清单校验：id 唯一/act 白名单/每幕组件白名单/act 序非降/首场 problem-card/幕覆盖。 */
    private static void checkScenes(JsonNode scenes, List<String> problems) {
        if (!scenes.isArray() || scenes.isEmpty()) {
            problems.add("骨架 scenes 缺失或不是非空数组");
            return;
        }
        Set<String> ids = new HashSet<>();
        Integer prevAct = null;
        boolean act2Knowledge = false;
        boolean act3Seen = false;
        boolean act4Seen = false;
        for (int i = 0; i < scenes.size(); i++) {
            JsonNode scene = scenes.get(i);
            if (!scene.isObject()) {
                problems.add("骨架 scenes[" + i + "] 不是对象");
                continue;
            }
            JsonNode id = scene.path("id");
            JsonNode act = scene.path("act");
            JsonNode component = scene.path("component");
            if (!id.isTextual()) {
                problems.add("骨架 scenes[" + i + "] 缺必需字段 id");
            } else if (!ids.add(id.asText())) {
                problems.add("骨架 scenes 场景 id 重复：" + id.asText());
            }
            if (!act.isInt() || !Set.of(2, 3, 4).contains(act.asInt())) {
                problems.add("骨架 scenes[" + i + "] act 不在 {2,3,4}");
                continue;
            }
            int actValue = act.asInt();
            if (prevAct != null && actValue < prevAct) {
                problems.add("骨架 scenes[" + i + "] act=" + actValue + " 出现在 act=" + prevAct + " 场景之后（act 序非降）");
            }
            prevAct = actValue;
            Set<String> allowed = StationChecks.ACT_COMPONENTS.get(actValue);
            if (!component.isTextual() || !StationChecks.COMPONENTS.contains(component.asText())) {
                problems.add("骨架 scenes[" + i + "] 组件 '" + (component.isTextual() ? component.asText() : "")
                        + "' 不在 7 组件白名单");
            } else if (!allowed.contains(component.asText())) {
                problems.add("骨架 scenes[" + i + "] 组件 '" + component.asText() + "' 不允许出现在 act" + actValue);
            }
            if (i == 0 && !"problem-card".equals(component.asText())) {
                problems.add("骨架 scenes[0] 组件应为 problem-card（首场）");
            }
            if (actValue == 2 && "knowledge-card".equals(component.asText())) {
                act2Knowledge = true;
            }
            act3Seen |= actValue == 3;
            act4Seen |= actValue == 4;
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
}
