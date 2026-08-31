package com.wyf.factory.stations;

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
 * GEN-P2 素材片工位（T18 分片生成第 2 片）：题干 + 骨架 → 四段素材
 * {@link Material}（knowledge/steps/pitfalls/generalMethod 正文）。
 *
 * <p>user 载荷 = {@code {"problemType":...,"problem":{lines},"plan":{"counts":{...},"anchors":[...]},"glossary":[...]}}；
 * errors 重载把上一轮失败清单逐条（"- " 前缀）追加在载荷尾部（通道不变）。</p>
 *
 * <p>骨架绑定校验（分片只领骨架，不得自行改计划）：四段条数与 counts 完全一致、
 * steps[i].usesAnchor 与 anchors[i] 逐位一致（锚点只领不造，Z变换类锚点误用单源化）、
 * 条目必需字段齐全 → 违反抛 retryable {@link GlmException}，清单即回传重试清单。</p>
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

    /** 题干 + 骨架 → 四段素材。 */
    public Material generate(ExtractResult extract, Skeleton skeleton) {
        return generate(extract, skeleton, List.of());
    }

    /** 错误清单回传重试：user 载荷尾部追加「上一轮校验失败清单（必须全部修正）」。 */
    public Material generate(ExtractResult extract, Skeleton skeleton, List<String> errors) {
        return parse(glm.chat(Prompts.MATERIAL, payload(extract, skeleton) + StationChecks.retrySuffix(errors)),
                skeleton);
    }

    private String payload(ExtractResult extract, Skeleton skeleton) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("problemType", extract.problemType());
            root.set("problem", mapper.valueToTree(extract.lines()));
            ObjectNode plan = root.putObject("plan");
            ObjectNode counts = plan.putObject("counts");
            counts.put("knowledge", skeleton.counts().knowledge());
            counts.put("steps", skeleton.counts().steps());
            counts.put("pitfalls", skeleton.counts().pitfalls());
            counts.put("generalMethod", skeleton.counts().generalMethod());
            plan.set("anchors", mapper.valueToTree(skeleton.anchors()));
            root.set("glossary", mapper.valueToTree(skeleton.glossary()));
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new GlmException("题干/骨架序列化失败", false, e);
        }
    }

    private Material parse(String raw, Skeleton skeleton) {
        JsonNode root;
        try {
            root = mapper.readTree(StationChecks.stripCodeFence(raw));
        } catch (IOException e) {
            throw new GlmException("素材片输出不是 JSON：原始响应=" + StationChecks.snippet(raw, RAW_SNIPPET), true, e);
        }
        List<String> problems = new ArrayList<>();
        StationChecks.count(root, "knowledge", StationChecks.KNOWLEDGE_MIN, StationChecks.KNOWLEDGE_MAX, problems);
        StationChecks.count(root, "steps", StationChecks.STEPS_MIN, StationChecks.STEPS_MAX, problems);
        StationChecks.count(root, "pitfalls", StationChecks.PITFALLS_MIN, StationChecks.PITFALLS_MAX, problems);
        StationChecks.count(root, "generalMethod", StationChecks.GENERAL_METHOD_MIN, StationChecks.GENERAL_METHOD_MAX, problems);
        checkPlanBinding(root, skeleton, problems);
        StationChecks.items(root, "knowledge", StationChecks.KNOWLEDGE_FIELDS, problems);
        StationChecks.items(root, "steps", StationChecks.STEP_FIELDS, problems);
        StationChecks.items(root, "pitfalls", StationChecks.PITFALL_FIELDS, problems);
        StationChecks.items(root, "generalMethod", StationChecks.METHOD_FIELDS, problems);
        if (!problems.isEmpty()) {
            throw new GlmException(problems, true);
        }
        try {
            return mapper.treeToValue(root, Material.class);
        } catch (IOException e) {
            throw new GlmException("素材片输出绑定失败：" + e.getMessage(), true, e);
        }
    }

    /** 骨架绑定校验：四段条数与骨架计划逐段一致、锚点与指派逐位一致。 */
    private static void checkPlanBinding(JsonNode root, Skeleton skeleton, List<String> problems) {
        Skeleton.Counts plan = skeleton.counts();
        checkSize(root, "knowledge", plan.knowledge(), problems);
        checkSize(root, "steps", plan.steps(), problems);
        checkSize(root, "pitfalls", plan.pitfalls(), problems);
        checkSize(root, "generalMethod", plan.generalMethod(), problems);

        JsonNode steps = root.path("steps");
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

    private static void checkSize(JsonNode root, String name, int planned, List<String> problems) {
        JsonNode arr = root.path(name);
        if (arr.isArray() && arr.size() != planned) {
            problems.add("素材 " + name + " 条数 " + arr.size() + " 与骨架计划 " + planned + " 不一致（条数以骨架为准）");
        }
    }
}
