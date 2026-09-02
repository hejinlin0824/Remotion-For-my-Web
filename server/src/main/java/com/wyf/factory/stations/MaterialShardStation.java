package com.wyf.factory.stations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.GlmException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * GEN-P2 素材片工位（T18 分片生成第 2 片，T30 一拆二）：按「数学核心 vs 周边散文」切成
 * 两个小请求（事故 14b37e5b：P2 全链最大单请求 5-6min 四连败于 GLM 长请求不稳窗口，
 * 拆小全部落回 ≤4-5min 安全包络；steps 步步依赖必须单请求生成，拆的是 steps vs 周边不是步与步）：
 *
 * <ul>
 *   <li><b>P2a 核心</b>（{@link #generateCore}）：题干 + 骨架（steps 计划+anchors）+ glossary →
 *       steps 独占。校验 = steps 条数=plan.steps、usesAnchor 与 anchors 逐位一致（锚点只领不造）、
 *       STEP_FIELDS 齐全。</li>
 *   <li><b>P2b 周边</b>（{@link #generateRest}）：P2a 同款基础载荷 + <b>P2a 的 steps 成品 JSON</b>
 *       （上下文指令：坑点/通法必须与实际 steps 一致）→ knowledge/pitfalls/generalMethod 三段。
 *       校验 = 三段条数与骨架计划逐段一致、字段齐全。</li>
 * </ul>
 *
 * <p>两片产出由 {@code GenShardPipeline} 在 Java 侧装配回完整 {@link Material}
 * （ContentMerger/下游零改动）。user 载荷 = {@code {"problemType":...,"problem":{lines},"plan":{...},"glossary":[...]}}
 * （P2b 多带 {@code "steps":[...]} 成品）；errors 重载把上一轮失败清单逐条（"- " 前缀）追加在
 * 载荷尾部（通道不变）。违反校验抛 retryable {@link GlmException}，清单即回传重试清单。</p>
 */
@Component
public class MaterialShardStation {

    /** 坏输出进异常消息的原始响应截断长度 */
    private static final int RAW_SNIPPET = 500;

    private final GlmClient glm;
    private final ObjectMapper mapper = new ObjectMapper();

    public MaterialShardStation(GlmClient glm) {
        this.glm = glm;
    }

    /** P2b 周边片产物：knowledge/pitfalls/generalMethod 三段（steps 由 P2a 产出，装配时并入 Material）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rest(List<Material.Knowledge> knowledge, List<Material.Pitfall> pitfalls,
                       List<Material.MethodItem> generalMethod) {
    }

    // ------------------------------------------------------------------ P2a 素材·核心片（steps 独占）

    /** P2a：题干 + 骨架（steps 计划+anchors）→ steps。 */
    public List<Material.Step> generateCore(ExtractResult extract, Skeleton skeleton) {
        return generateCore(extract, skeleton, List.of());
    }

    /** 错误清单回传重试：user 载荷尾部追加「上一轮校验失败清单（必须全部修正）」。 */
    public List<Material.Step> generateCore(ExtractResult extract, Skeleton skeleton, List<String> errors) {
        return parseCore(glm.chat(Prompts.MATERIAL_CORE,
                corePayload(extract, skeleton) + StationChecks.retrySuffix(errors)), skeleton);
    }

    private String corePayload(ExtractResult extract, Skeleton skeleton) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("problemType", extract.problemType());
            root.set("problem", mapper.valueToTree(extract.lines()));
            ObjectNode plan = root.putObject("plan");
            plan.putObject("counts").put("steps", skeleton.counts().steps());
            plan.set("anchors", mapper.valueToTree(skeleton.anchors()));
            root.set("glossary", mapper.valueToTree(skeleton.glossary()));
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new GlmException("题干/骨架序列化失败", false, e);
        }
    }

    private List<Material.Step> parseCore(String raw, Skeleton skeleton) {
        JsonNode root;
        try {
            root = mapper.readTree(StationChecks.stripCodeFence(raw));
        } catch (IOException e) {
            throw new GlmException("素材核心片输出不是 JSON：原始响应=" + StationChecks.snippet(raw, RAW_SNIPPET), true, e);
        }
        List<String> problems = new ArrayList<>();
        StationChecks.count(root, "steps", StationChecks.STEPS_MIN, StationChecks.STEPS_MAX, problems);
        checkCoreBinding(root, skeleton, problems);
        StationChecks.items(root, "steps", StationChecks.STEP_FIELDS, problems);
        if (!problems.isEmpty()) {
            throw new GlmException(problems, true);
        }
        try {
            Material.Step[] steps = mapper.treeToValue(root.path("steps"), Material.Step[].class);
            return List.of(steps);
        } catch (IOException e) {
            throw new GlmException("素材核心片输出绑定失败：" + e.getMessage(), true, e);
        }
    }

    /** P2a 骨架绑定校验：steps 条数与骨架计划一致、锚点与指派逐位一致（自原 checkPlanBinding 的 steps 部分原样迁移）。 */
    private static void checkCoreBinding(JsonNode root, Skeleton skeleton, List<String> problems) {
        JsonNode steps = root.path("steps");
        int planned = skeleton.counts().steps();
        if (steps.isArray() && steps.size() != planned) {
            problems.add("素材 steps 条数 " + steps.size() + " 与骨架计划 " + planned + " 不一致（条数以骨架为准）");
        }

        List<String> anchors = skeleton.anchors() == null ? List.of() : skeleton.anchors();
        if (steps.isArray() && steps.size() == anchors.size()) {
            for (int i = 0; i < steps.size(); i++) {
                JsonNode anchor = steps.get(i).path("usesAnchor");
                String assigned = anchors.get(i);
                if (!anchor.isTextual() || !assigned.equals(anchor.asText())) {
                    problems.add("素材 steps[" + i + "].usesAnchor='" + (anchor.isTextual() ? anchor.asText() : "")
                            + "' 与骨架指派 '" + assigned + "' 不一致（锚点只能领用骨架指派，不得自行改锚）");
                }
            }
        }
    }

    // ------------------------------------------------------------------ P2b 素材·周边片（三段）

    /** P2b：P2a 同款基础载荷 + steps 成品（上下文）→ knowledge/pitfalls/generalMethod。 */
    public Rest generateRest(ExtractResult extract, Skeleton skeleton, List<Material.Step> steps) {
        return generateRest(extract, skeleton, steps, List.of());
    }

    /** 错误清单回传重试：user 载荷尾部追加「上一轮校验失败清单（必须全部修正）」。 */
    public Rest generateRest(ExtractResult extract, Skeleton skeleton, List<Material.Step> steps, List<String> errors) {
        return parseRest(glm.chat(Prompts.MATERIAL_REST,
                restPayload(extract, skeleton, steps) + StationChecks.retrySuffix(errors)), skeleton);
    }

    private String restPayload(ExtractResult extract, Skeleton skeleton, List<Material.Step> steps) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("problemType", extract.problemType());
            root.set("problem", mapper.valueToTree(extract.lines()));
            ObjectNode plan = root.putObject("plan");
            ObjectNode counts = plan.putObject("counts");
            counts.put("knowledge", skeleton.counts().knowledge());
            counts.put("pitfalls", skeleton.counts().pitfalls());
            counts.put("generalMethod", skeleton.counts().generalMethod());
            plan.set("anchors", mapper.valueToTree(skeleton.anchors()));
            root.set("steps", mapper.valueToTree(steps));   // P2a 成品：坑点/通法必须与实际 steps 一致
            root.set("glossary", mapper.valueToTree(skeleton.glossary()));
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new GlmException("题干/骨架/steps 序列化失败", false, e);
        }
    }

    private Rest parseRest(String raw, Skeleton skeleton) {
        JsonNode root;
        try {
            root = mapper.readTree(StationChecks.stripCodeFence(raw));
        } catch (IOException e) {
            throw new GlmException("素材周边片输出不是 JSON：原始响应=" + StationChecks.snippet(raw, RAW_SNIPPET), true, e);
        }
        List<String> problems = new ArrayList<>();
        StationChecks.count(root, "knowledge", StationChecks.KNOWLEDGE_MIN, StationChecks.KNOWLEDGE_MAX, problems);
        StationChecks.count(root, "pitfalls", StationChecks.PITFALLS_MIN, StationChecks.PITFALLS_MAX, problems);
        StationChecks.count(root, "generalMethod", StationChecks.GENERAL_METHOD_MIN, StationChecks.GENERAL_METHOD_MAX, problems);
        checkRestBinding(root, skeleton, problems);
        StationChecks.items(root, "knowledge", StationChecks.KNOWLEDGE_FIELDS, problems);
        StationChecks.items(root, "pitfalls", StationChecks.PITFALL_FIELDS, problems);
        StationChecks.items(root, "generalMethod", StationChecks.METHOD_FIELDS, problems);
        if (!problems.isEmpty()) {
            throw new GlmException(problems, true);
        }
        try {
            return mapper.treeToValue(root, Rest.class);
        } catch (IOException e) {
            throw new GlmException("素材周边片输出绑定失败：" + e.getMessage(), true, e);
        }
    }

    /** P2b 骨架绑定校验：三段条数与骨架计划逐段一致。 */
    private static void checkRestBinding(JsonNode root, Skeleton skeleton, List<String> problems) {
        Skeleton.Counts plan = skeleton.counts();
        checkSize(root, "knowledge", plan.knowledge(), problems);
        checkSize(root, "pitfalls", plan.pitfalls(), problems);
        checkSize(root, "generalMethod", plan.generalMethod(), problems);
    }

    private static void checkSize(JsonNode root, String name, int planned, List<String> problems) {
        JsonNode arr = root.path(name);
        if (arr.isArray() && arr.size() != planned) {
            problems.add("素材 " + name + " 条数 " + arr.size() + " 与骨架计划 " + planned + " 不一致（条数以骨架为准）");
        }
    }
}
