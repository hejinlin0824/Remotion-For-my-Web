package com.wyf.factory.stations;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * MATERIALIZED 工位产物：讲题视频四段素材（template/README.md §3.1 契约，
 * ASSEMBLED 工位消费）。字段语义与条数范围（knowledge 2-4 / steps 3-10 /
 * pitfalls 1-3 / generalMethod 3-6）由 MaterialStation 校验。
 *
 * @param knowledge     知识点回顾（claim/formula/premise/trap）
 * @param steps         解题步骤（usesAnchor 指向题干行 id）
 * @param pitfalls      易错警示（claim/why）
 * @param generalMethod 通用方法论（step/trick）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Material(
        List<Knowledge> knowledge, List<Step> steps,
        List<Pitfall> pitfalls, List<MethodItem> generalMethod) {

    /** 一条知识点：断言 + KaTeX 公式 + 使用前提 + 常见陷阱。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Knowledge(String claim, String formula, String premise, String trap) {
    }

    /** 一步解题：锚点题干行 id + 本步陈述 + KaTeX 推导 + 提示。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Step(String usesAnchor, String statement, String derivation, String note) {
    }

    /** 一条易错点：错误做法 + 为什么错。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Pitfall(String claim, String why) {
    }

    /** 通用方法论一步：做什么 + 口诀/技巧。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MethodItem(String step, String trick) {
    }
}
