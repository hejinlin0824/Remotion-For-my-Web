package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.GlmException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ASSEMBLED 剧本工位：ExtractResult + Material → ContentJson（完整 content.json 形状，spec §9）。
 *
 * <p>user 载荷 = {@code {"problem":题干 JSON,"material":素材 JSON}}；errors 重载把上一轮
 * 校验失败清单逐条（"- " 前缀）追加在载荷尾部，供回传重试。</p>
 *
 * <p>绑定校验（工位级轻校验，重活归 V1/T7）：meta/problem/scenes 缺必需字段、四段条数越界
 * → {@link GlmException}(retryable=true)，消息逐条列出具体差异（即回传重试的错误清单）。
 * scenes 的 ref 越界/白名单/顺序等结构重校验归 V1。</p>
 */
@Component
public class ScriptStation {

    private static final Set<String> KNOWLEDGE_FIELDS = Set.of("claim", "formula", "premise", "trap");
    private static final Set<String> STEP_FIELDS = Set.of("usesAnchor", "statement", "derivation", "note");
    private static final Set<String> PITFALL_FIELDS = Set.of("claim", "why");
    private static final Set<String> METHOD_FIELDS = Set.of("step", "trick");
    private static final Set<String> SEG_TYPES = Set.of("text", "math");
    /** 坏输出进异常消息的原始响应截断长度 */
    private static final int RAW_SNIPPET = 500;

    private final GlmClient glm;
    private final ObjectMapper mapper = new ObjectMapper();

    public ScriptStation(GlmClient glm) {
        this.glm = glm;
    }

    /** 题干 + 素材 → ContentJson。 */
    public ContentJson assemble(ExtractResult extract, Material material) {
        return assemble(extract, material, List.of());
    }

    /** 错误清单回传重试：user 载荷尾部追加「上一轮校验失败清单（必须全部修正）」。 */
    public ContentJson assemble(ExtractResult extract, Material material, List<String> errors) {
        return parse(glm.chat(Prompts.SCRIPT, userPayload(extract, material) + MaterialStation.retrySuffix(errors)));
    }

    private String userPayload(ExtractResult extract, Material material) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.set("problem", mapper.valueToTree(extract));
            payload.set("material", mapper.valueToTree(material));
            return mapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new GlmException("题干/素材序列化失败", false, e);
        }
    }

    private ContentJson parse(String raw) {
        JsonNode root;
        try {
            root = mapper.readTree(stripCodeFence(raw));
        } catch (IOException e) {
            throw new GlmException("剧本输出不是 JSON：原始响应=" + snippet(raw), true, e);
        }
        List<String> problems = new ArrayList<>();
        validateMeta(root.path("meta"), problems);
        validateProblem(root.path("problem"), problems);
        MaterialStation.count(root, "knowledge", MaterialStation.KNOWLEDGE_MIN, MaterialStation.KNOWLEDGE_MAX, problems);
        MaterialStation.count(root, "steps", MaterialStation.STEPS_MIN, MaterialStation.STEPS_MAX, problems);
        MaterialStation.count(root, "pitfalls", MaterialStation.PITFALLS_MIN, MaterialStation.PITFALLS_MAX, problems);
        MaterialStation.count(root, "generalMethod", MaterialStation.GENERAL_METHOD_MIN, MaterialStation.GENERAL_METHOD_MAX, problems);
        MaterialStation.items(root, "knowledge", KNOWLEDGE_FIELDS, problems);
        MaterialStation.items(root, "steps", STEP_FIELDS, problems);
        MaterialStation.items(root, "pitfalls", PITFALL_FIELDS, problems);
        MaterialStation.items(root, "generalMethod", METHOD_FIELDS, problems);
        validateScenes(root.path("scenes"), problems);
        if (!problems.isEmpty()) {
            throw new GlmException(String.join("\n", problems), true);
        }
        try {
            return mapper.treeToValue(root, ContentJson.class);
        } catch (IOException e) {
            throw new GlmException("剧本输出绑定失败：" + e.getMessage(), true, e);
        }
    }

    private static void validateMeta(JsonNode meta, List<String> problems) {
        if (!meta.path("aspect").isTextual()) {
            problems.add("meta 缺必需字段 aspect");
        }
        if (!meta.path("problemType").isTextual()) {
            problems.add("meta 缺必需字段 problemType");
        }
    }

    /** 题干轻校验：lines 非空，每行有 id 与非空 segments，每段 type∈{text,math} 且 value 为字符串。 */
    private static void validateProblem(JsonNode problem, List<String> problems) {
        JsonNode lines = problem.path("lines");
        if (!lines.isArray() || lines.isEmpty()) {
            problems.add("problem.lines 缺失或为空");
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            JsonNode line = lines.get(i);
            if (!line.path("id").isTextual()) {
                problems.add("problem.lines[" + i + "] 缺必需字段 id");
            }
            JsonNode segments = line.path("segments");
            if (!segments.isArray() || segments.isEmpty()) {
                problems.add("problem.lines[" + i + "] segments 缺失或为空");
                continue;
            }
            for (int j = 0; j < segments.size(); j++) {
                JsonNode seg = segments.get(j);
                if (!seg.path("type").isTextual() || !SEG_TYPES.contains(seg.path("type").asText())
                        || !seg.path("value").isTextual()) {
                    problems.add("problem.lines[" + i + "].segments[" + j + "] 缺 type/value 或 type 非法");
                }
            }
        }
    }

    /** scenes 轻校验：非空数组，每场必备 id/act/component/ttsText/props（act 为整数）。 */
    private static void validateScenes(JsonNode scenes, List<String> problems) {
        if (!scenes.isArray() || scenes.isEmpty()) {
            problems.add("scenes 缺失或为空");
            return;
        }
        for (int i = 0; i < scenes.size(); i++) {
            JsonNode scene = scenes.get(i);
            if (!scene.isObject()) {
                problems.add("scenes[" + i + "] 不是对象");
                continue;
            }
            if (!scene.path("id").isTextual()) {
                problems.add("scenes[" + i + "] 缺必需字段 id");
            }
            if (!scene.path("act").isInt()) {
                problems.add("scenes[" + i + "] 缺必需字段 act 或不是整数");
            }
            if (!scene.path("component").isTextual()) {
                problems.add("scenes[" + i + "] 缺必需字段 component");
            }
            JsonNode ttsText = scene.path("ttsText");
            if (!ttsText.isTextual() || ttsText.asText().isBlank()) {
                problems.add("scenes[" + i + "] 缺必需字段 ttsText");
            }
            if (!scene.path("props").isObject()) {
                problems.add("scenes[" + i + "] 缺必需字段 props");
            }
        }
    }

    /** 剥 ```/```json 代码块围栏（与 ExtractStation 同规则）。 */
    private static String stripCodeFence(String raw) {
        String s = raw.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            s = firstNewline >= 0 ? s.substring(firstNewline + 1) : "";
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3);
        }
        return s.strip();
    }

    private static String snippet(String raw) {
        return raw.length() > RAW_SNIPPET ? raw.substring(0, RAW_SNIPPET) : raw;
    }
}
