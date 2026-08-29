package com.wyf.factory.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.stations.Material;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * content.json 契约 POJO（template/README.md §3.1，ASSEMBLED 工位产物，T7 覆写副本用）。
 * 字段名与封版模板 template/src/data/content.json（golden）逐字一致；props 按组件键不同用
 * Map 承载（problem-card {} / knowledge-card knowledgeRef / step-card stepRef /
 * derivation-popup stepRef+formula / pitfall-card pitfallRef / checklist-card pitfallRefs /
 * general-list itemRef）。LLM 输出容忍未知字段（ignoreUnknown），重活校验归 V1（T7）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContentJson(
        Meta meta,
        Problem problem,
        List<Material.Knowledge> knowledge,
        List<Material.Step> steps,
        List<Material.Pitfall> pitfalls,
        List<Material.MethodItem> generalMethod,
        List<Scene> scenes) {

    /** 画幅与题型记录。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(String aspect, String problemType) {
    }

    /** 题干（与 EXTRACTING 工位 ExtractResult.problem/lines 同形）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Problem(List<Line> lines) {
    }

    /** 题目一行：锚点 id + 行内段序列。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Line(String id, List<Seg> segments) {
    }

    /** 行内段：text=中文叙述，math=数学内容（KaTeX/LaTeX）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Seg(String type, String value) {
    }

    /** 一场镜头：id=s01 递增，act∈{2,3,4}，component 白名单，props 按组件。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Scene(String id, int act, String component, String ttsText, Map<String, Object> props) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 紧凑 JSON（无换行缩进，UTF-8 语义），字段顺序按声明序 = golden 惯例序。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(this);
        } catch (IOException e) {
            throw new IllegalStateException("content.json 序列化失败", e);
        }
    }

    /** 从盘上读回（断点续跑：workspace/{jobId}/src/data/content.json），IO 失败包 UncheckedIOException。 */
    public static ContentJson readFrom(Path file) {
        try {
            return MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), ContentJson.class);
        } catch (IOException e) {
            throw new UncheckedIOException("content.json 读取失败：" + file, e);
        }
    }
}
