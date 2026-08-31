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

/**
 * GEN-P1 题干片工位（T18 分片生成第 1 片）：ExtractResult 题干 → content.json 的
 * problem 段（{"lines":[...]}）。职责 = 题干 text/math 分段排版；禁改写题干数学内容。
 *
 * <p>保真校验（V2Fidelity 口径镜像，工位级先拦防止烧驳回轮）：行数/行 id/段数/段类型
 * 与输入完全一致；math 段去空白后逐字符相等；text 段归一化后相等。违反抛 retryable
 * {@link GlmException}，差异清单即回传重试清单。</p>
 */
@Component
public class ProblemSliceStation {

    /** 坏输出进异常消息的原始响应截断长度 */
    private static final int RAW_SNIPPET = 500;

    private final GlmClient glm;
    private final ObjectMapper mapper = new ObjectMapper();

    public ProblemSliceStation(GlmClient glm) {
        this.glm = glm;
    }

    /** 题干 → 排版后的 problem.lines。 */
    public List<ContentJson.Line> format(ExtractResult extract) {
        return format(extract, List.of());
    }

    /** 错误清单回传重试：user 载荷尾部追加「上一轮校验失败清单（必须全部修正）」。 */
    public List<ContentJson.Line> format(ExtractResult extract, List<String> errors) {
        return parse(glm.chat(Prompts.PROBLEM_SLICE, payload(extract) + StationChecks.retrySuffix(errors)), extract);
    }

    private String payload(ExtractResult extract) {
        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("problemType", extract.problemType());
            payload.set("lines", mapper.valueToTree(extract.lines()));
            return mapper.writeValueAsString(payload);
        } catch (IOException e) {
            throw new GlmException("题干序列化失败", false, e);
        }
    }

    private List<ContentJson.Line> parse(String raw, ExtractResult extract) {
        JsonNode root;
        try {
            root = mapper.readTree(StationChecks.stripCodeFence(raw));
        } catch (IOException e) {
            throw new GlmException("题干片输出不是 JSON：原始响应=" + StationChecks.snippet(raw, RAW_SNIPPET), true, e);
        }
        List<String> problems = new ArrayList<>();
        checkFidelity(root.path("lines"), extract, problems);
        if (!problems.isEmpty()) {
            throw new GlmException(problems, true);
        }
        try {
            return List.of(mapper.treeToValue(root.path("lines"), ContentJson.Line[].class));
        } catch (IOException e) {
            throw new GlmException("题干片输出绑定失败：" + e.getMessage(), true, e);
        }
    }

    /** V2 口径镜像校验：行数/id/段数/段类型全等，math 去空白相等、text 归一化相等。 */
    private static void checkFidelity(JsonNode lines, ExtractResult extract, List<String> problems) {
        if (!lines.isArray() || lines.isEmpty()) {
            problems.add("题干片 lines 缺失或不是非空数组");
            return;
        }
        List<ExtractResult.Line> source = extract.lines();
        if (lines.size() != source.size()) {
            problems.add("题干片 行数不一致：输出 " + lines.size() + " 行 vs 输入 " + source.size() + " 行");
            return;
        }
        for (int i = 0; i < source.size(); i++) {
            ExtractResult.Line expected = source.get(i);
            JsonNode line = lines.get(i);
            if (!line.path("id").isTextual() || !expected.id().equals(line.path("id").asText())) {
                problems.add("题干片 第 " + (i + 1) + " 行 id 不一致：输出='"
                        + line.path("id").asText("") + "' vs 输入='" + expected.id() + "'");
                continue;
            }
            List<ExtractResult.Seg> segs = expected.segments() == null ? List.of() : expected.segments();
            JsonNode segments = line.path("segments");
            if (!segments.isArray() || segments.size() != segs.size()) {
                problems.add("题干片 " + expected.id() + " 段数不一致：输出 "
                        + (segments.isArray() ? segments.size() : 0) + " 段 vs 输入 " + segs.size() + " 段");
                continue;
            }
            for (int j = 0; j < segs.size(); j++) {
                ExtractResult.Seg expectedSeg = segs.get(j);
                JsonNode seg = segments.get(j);
                String type = seg.path("type").asText("");
                if (!expectedSeg.type().equals(type)) {
                    problems.add("题干片 " + expected.id() + " 段 " + (j + 1) + " 类型不一致：输出="
                            + type + " vs 输入=" + expectedSeg.type());
                    continue;
                }
                boolean same = "math".equals(type)
                        ? StationChecks.stripWhitespace(seg.path("value").asText())
                                .equals(StationChecks.stripWhitespace(expectedSeg.value()))
                        : StationChecks.normalizeText(seg.path("value").asText())
                                .equals(StationChecks.normalizeText(expectedSeg.value()));
                if (!same) {
                    problems.add("题干片 " + expected.id() + " 段 " + (j + 1) + " 内容被改写（保真红线）：输出='"
                            + snippet(seg.path("value").asText("")) + "' vs 输入='" + snippet(expectedSeg.value()) + "'");
                }
            }
        }
    }

    private static String snippet(String value) {
        return value.length() > 80 ? value.substring(0, 80) : value;
    }
}
