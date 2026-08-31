package com.wyf.factory.stations;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Set;

/**
 * 分片工位共享轻校验工具（T18 分片生成重构：原 MaterialStation/ScriptStation 的静态校验
 * 合并迁此，骨架协调者/题干片/素材片/场景片/合并器复用同规则）。
 *
 * <p>工位级轻校验定位不变：只查形状（必需字段/条数范围/类型），重活归 V1-V4。
 * 条数范围常量即 template/README.md §3 契约（原 MaterialStation MIN/MAX，T19a 前既有）。</p>
 */
final class StationChecks {

    /** 条数范围（template/README.md §3；骨架协调者与合并器校验同范围）。 */
    static final int KNOWLEDGE_MIN = 2, KNOWLEDGE_MAX = 4;
    static final int STEPS_MIN = 3, STEPS_MAX = 10;
    static final int PITFALLS_MIN = 1, PITFALLS_MAX = 3;
    static final int GENERAL_METHOD_MIN = 3, GENERAL_METHOD_MAX = 6;

    static final Set<String> KNOWLEDGE_FIELDS = Set.of("claim", "formula", "premise", "trap");
    static final Set<String> STEP_FIELDS = Set.of("usesAnchor", "statement", "derivation", "note");
    static final Set<String> PITFALL_FIELDS = Set.of("claim", "why");
    static final Set<String> METHOD_FIELDS = Set.of("step", "trick");
    static final Set<String> SEG_TYPES = Set.of("text", "math");

    /** 题型白名单（V1/meta 同表，工位级先拦） */
    static final Set<String> PROBLEM_TYPES = Set.of("基础题", "计算题", "证明题", "应用题");
    /** 7 组件白名单与每幕允许组件（V1Structural ACT_COMPONENTS 同表，骨架级先拦） */
    static final Set<String> COMPONENTS = Set.of("problem-card", "knowledge-card", "step-card",
            "derivation-popup", "pitfall-card", "checklist-card", "general-list");
    static final java.util.Map<Integer, Set<String>> ACT_COMPONENTS = java.util.Map.of(
            2, Set.of("problem-card", "knowledge-card"),
            3, Set.of("step-card", "derivation-popup", "pitfall-card", "checklist-card"),
            4, Set.of("general-list"));

    private StationChecks() {
    }

    /** 段级条数校验：缺失/非数组/越界各记一条差异（原 MaterialStation.count，合并器/分片复用）。 */
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

    /** 条目级字段校验：非对象或缺任一必需字段（非字符串）逐条记差异（原 MaterialStation.items）。 */
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

    /** meta 轻校验（原 ScriptStation.validateMeta）：aspect/problemType 必需且为字符串。 */
    static void validateMeta(JsonNode meta, List<String> problems) {
        if (!meta.path("aspect").isTextual()) {
            problems.add("meta 缺必需字段 aspect");
        }
        if (!meta.path("problemType").isTextual()) {
            problems.add("meta 缺必需字段 problemType");
        }
    }

    /**
     * 题干轻校验（原 ScriptStation.validateProblem）：lines 非空，每行有 id 与非空 segments，
     * 每段 type∈{text,math} 且 value 为字符串。
     */
    static void validateProblem(JsonNode problem, List<String> problems) {
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

    /** scenes 轻校验（原 ScriptStation.validateScenes）：非空数组，每场必备 id/act/component/ttsText/props（act 为整数）。 */
    static void validateScenes(JsonNode scenes, List<String> problems) {
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

    /**
     * 重试载荷尾部（原 MaterialStation.retrySuffix，错误回传通道保持）：逐条 "- " 前缀的
     * 失败清单（无错误时为空串）。
     */
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

    /** 剥 ```/```json 代码块围栏（原三工位同规则收敛于此）。 */
    static String stripCodeFence(String raw) {
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

    /** 坏输出进异常消息的原始响应截断（原三工位同规则收敛于此）。 */
    static String snippet(String raw, int max) {
        return raw.length() > max ? raw.substring(0, max) : raw;
    }

    /**
     * text 段归一化（V2Fidelity.normalize 镜像，工位级预检对齐 V2 口径）：
     * 全角空格/不断行空格及全部空白去除；全角 ASCII 区映射半角；。、弯引号映射 ASCII；连续标点不折叠。
     */
    static String normalizeText(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (isSpace(c)) {
                continue;
            }
            if (c >= '！' && c <= '～') {
                c = (char) (c - 0xFEE0);
            }
            switch (c) {
                case '。' -> c = '.';
                case '、' -> c = ',';
                case '‘', '’' -> c = '\'';
                case '“', '”' -> c = '"';
                default -> { }
            }
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** math 段只去全部空白（V2Fidelity 口径镜像：LaTeX 大小写敏感、标点不映射）。 */
    static String stripWhitespace(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isSpace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static boolean isSpace(char c) {
        return c == '　' || c == ' ' || c == ' ' || c == ' ' || Character.isWhitespace(c);
    }
}
