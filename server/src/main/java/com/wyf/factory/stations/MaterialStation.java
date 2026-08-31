package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.glm.GlmClient;
import com.wyf.factory.glm.GlmException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * MATERIALIZED 素材工位：ExtractResult → Material 四段（spec §9）。
 *
 * <p>user 载荷 = 题干 JSON（ExtractResult 序列化）；errors 重载把上一轮校验失败清单
 * 逐条（"- " 前缀）追加在载荷尾部，供回传重试。</p>
 *
 * <p>绑定校验（工位级轻校验，重活归 V1/T7）：GLM 输出缺必需字段/条数越界
 * （knowledge 2-4 / steps 3-10 / pitfalls 1-3 / generalMethod 3-6）→
 * {@link GlmException}(retryable=true)，消息逐条列出具体差异（即回传重试的错误清单）。</p>
 */
@Component
public class MaterialStation {

    /** 条数范围（template/README.md §3；ScriptStation 校验同范围，复用此常量）。 */
    static final int KNOWLEDGE_MIN = 2, KNOWLEDGE_MAX = 4;
    static final int STEPS_MIN = 3, STEPS_MAX = 10;
    static final int PITFALLS_MIN = 1, PITFALLS_MAX = 3;
    static final int GENERAL_METHOD_MIN = 3, GENERAL_METHOD_MAX = 6;

    private static final Set<String> KNOWLEDGE_FIELDS = Set.of("claim", "formula", "premise", "trap");
    private static final Set<String> STEP_FIELDS = Set.of("usesAnchor", "statement", "derivation", "note");
    private static final Set<String> PITFALL_FIELDS = Set.of("claim", "why");
    private static final Set<String> METHOD_FIELDS = Set.of("step", "trick");
    /** 坏输出进异常消息的原始响应截断长度 */
    private static final int RAW_SNIPPET = 500;

    private final GlmClient glm;
    private final ObjectMapper mapper = new ObjectMapper();

    public MaterialStation(GlmClient glm) {
        this.glm = glm;
    }

    /** 题干 → Material。 */
    public Material generate(ExtractResult extract) {
        return generate(extract, List.of());
    }

    /** 错误清单回传重试：user 载荷尾部追加「上一轮校验失败清单（必须全部修正）」。 */
    public Material generate(ExtractResult extract, List<String> errors) {
        return parse(glm.chat(Prompts.MATERIAL, userPayload(extract) + retrySuffix(errors)));
    }

    private String userPayload(ExtractResult extract) {
        try {
            return mapper.writeValueAsString(extract);
        } catch (IOException e) {
            throw new GlmException("题干序列化失败", false, e);
        }
    }

    /** 重试载荷尾部：逐条 "- " 前缀的失败清单（无错误时为空串）。 */
    static String retrySuffix(List<String> errors) {
        if (errors == null || errors.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n上一轮校验失败清单（必须全部修正）：");
        for (String error : errors) {
            sb.append("\n- ").append(error);
        }
        return sb.toString();
    }

    private Material parse(String raw) {
        JsonNode root;
        try {
            root = mapper.readTree(stripCodeFence(raw));
        } catch (IOException e) {
            throw new GlmException("素材输出不是 JSON：原始响应=" + snippet(raw), true, e);
        }
        List<String> problems = new ArrayList<>();
        count(root, "knowledge", KNOWLEDGE_MIN, KNOWLEDGE_MAX, problems);
        count(root, "steps", STEPS_MIN, STEPS_MAX, problems);
        count(root, "pitfalls", PITFALLS_MIN, PITFALLS_MAX, problems);
        count(root, "generalMethod", GENERAL_METHOD_MIN, GENERAL_METHOD_MAX, problems);
        items(root, "knowledge", KNOWLEDGE_FIELDS, problems);
        items(root, "steps", STEP_FIELDS, problems);
        items(root, "pitfalls", PITFALL_FIELDS, problems);
        items(root, "generalMethod", METHOD_FIELDS, problems);
        if (!problems.isEmpty()) {
            throw new GlmException(problems, true);   // T19a：结构化携带清单（message 形状不变）
        }
        try {
            return mapper.treeToValue(root, Material.class);
        } catch (IOException e) {
            throw new GlmException("素材输出绑定失败：" + e.getMessage(), true, e);
        }
    }

    /** 段级条数校验：缺失/非数组/越界各记一条差异（ScriptStation 复用同规则）。 */
    static void count(JsonNode root, String name, int min, int max, List<String> problems) {
        JsonNode arr = root.path(name);
        if (!arr.isArray() || arr.isEmpty()) {
            problems.add(name + " 缺失或不是非空数组");
            return;
        }
        if (arr.size() < min || arr.size() > max) {
            problems.add(name + " 条数 " + arr.size() + " 超出范围 " + min + "-" + max);
        }
    }

    /** 条目级字段校验：非对象或缺任一必需字段（非字符串）逐条记差异（ScriptStation 复用）。 */
    static void items(JsonNode root, String name, Set<String> fields, List<String> problems) {
        JsonNode arr = root.path(name);
        if (!arr.isArray()) {
            return;
        }
        for (int i = 0; i < arr.size(); i++) {
            JsonNode item = arr.get(i);
            if (!item.isObject()) {
                problems.add(name + "[" + i + "] 不是对象");
                continue;
            }
            for (String field : fields) {
                if (!item.path(field).isTextual()) {
                    problems.add(name + "[" + i + "] 缺必需字段 " + field);
                }
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
