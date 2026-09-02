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
 * EXTRACTING 审题工位（spec §9）：文本原题/截图 → ExtractResult(problemType, lines)。
 *
 * <p>TEXT 路径 user 载荷 = 原题文本；IMAGE 路径走 {@link GlmClient#chatWithImage}。
 * GLM 返回 JSON 容忍 ```json 代码块包裹（先剥围栏再解析）。</p>
 *
 * <p>失败语义：{@code {"error":"..."}} → {@link FatalExtractException}（读不出题/非可讲解题目，
 * 换素材重试，同素材不重试）；非 JSON/缺字段/段结构非法 → {@link GlmException}(retryable=true)
 * （消息含原始响应截断 500 字符），由调用方按内容工位重试策略整体重试。</p>
 */
@Component
public class ExtractStation {

    /** 审题判死：GLM 明确说读不出题/不是可讲解的考研题目（T28 科目中性化），重试同一素材无意义。 */
    public static final class FatalExtractException extends RuntimeException {
        public FatalExtractException(String message) {
            super(message);
        }
    }

    private static final Set<String> SEG_TYPES = Set.of("text", "math");
    /** 坏输出进异常消息的原始响应截断长度 */
    private static final int RAW_SNIPPET = 500;

    private final GlmClient glm;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExtractStation(GlmClient glm) {
        this.glm = glm;
    }

    /** TEXT 路径：原题文本 → ExtractResult。 */
    public ExtractResult extract(String textPayload) {
        return parse(glm.chat(Prompts.EXTRACT, textPayload));
    }

    /** IMAGE 路径：截图 base64 + mime → ExtractResult。 */
    public ExtractResult extractImage(String imageBase64, String mime) {
        return parse(glm.chatWithImage(Prompts.EXTRACT, imageBase64, mime));
    }

    private ExtractResult parse(String raw) {
        JsonNode root;
        try {
            root = mapper.readTree(stripCodeFence(raw));
        } catch (IOException e) {
            throw new GlmException("审题输出不是 JSON：原始响应=" + snippet(raw), true, e);
        }
        if (root.path("error").isTextual()) {
            throw new FatalExtractException("审题失败：" + root.get("error").asText());
        }
        // T27 废题判定（TEXT/IMAGE 两通道同规则；T28 判废口径放开到考研科目范围）：模型判非可讲解题目 → 废题驳回（Fatal 通道，
        // 不烧 extractRetries——同素材重试无意义）；reason 缺失兜底，绝不让空原因穿出
        if (root.path("notQuestion").asBoolean(false)) {
            String reason = root.path("reason").asText("").strip();
            throw new FatalExtractException("上传/输入内容不是可讲解的考研题目：" + (reason.isEmpty() ? "未说明原因" : reason));
        }
        JsonNode problemType = root.path("problemType");
        JsonNode lines = root.path("lines");
        if (!problemType.isTextual() || !lines.isArray() || lines.isEmpty()) {
            throw new GlmException("审题输出缺 problemType/lines：原始响应=" + snippet(raw), true);
        }
        List<ExtractResult.Line> parsed = new ArrayList<>();
        for (JsonNode line : lines) {
            parsed.add(parseLine(line, raw));
        }
        return new ExtractResult(problemType.asText(), List.copyOf(parsed));
    }

    private ExtractResult.Line parseLine(JsonNode line, String raw) {
        JsonNode id = line.path("id");
        JsonNode segments = line.path("segments");
        if (!id.isTextual() || !segments.isArray() || segments.isEmpty()) {
            throw new GlmException("审题输出 line 缺 id/segments：原始响应=" + snippet(raw), true);
        }
        List<ExtractResult.Seg> segs = new ArrayList<>();
        for (JsonNode seg : segments) {
            JsonNode type = seg.path("type");
            JsonNode value = seg.path("value");
            if (!type.isTextual() || !value.isTextual() || !SEG_TYPES.contains(type.asText())) {
                throw new GlmException("审题输出 segment 缺 type/value 或 type 非法：原始响应=" + snippet(raw), true);
            }
            segs.add(new ExtractResult.Seg(type.asText(), value.asText()));
        }
        return new ExtractResult.Line(id.asText(), List.copyOf(segs));
    }

    /** 剥 ```/```json 代码块围栏（容忍首尾空白与无语言标注的围栏）。 */
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
