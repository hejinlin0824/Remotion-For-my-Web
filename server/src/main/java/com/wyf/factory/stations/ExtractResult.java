package com.wyf.factory.stations;

import java.util.List;

/**
 * EXTRACTING 工位产物：审题后的结构化题目（对应 template/src/data/content.json 的
 * meta.problemType + problem.lines 契约，后续 MATERIALIZED/ASSEMBLED 工位消费）。
 *
 * @param problemType 题型，取值 ∈ {"基础题","计算题","证明题","应用题"}
 * @param lines       题目按行拆分，id 从 "L1" 递增
 */
public record ExtractResult(String problemType, List<Line> lines) {

    /** 一行题目：锚点 id + 行内段序列。 */
    public record Line(String id, List<Seg> segments) {
    }

    /** 行内段：text=中文叙述，math=数学内容（LaTeX，无 $ 定界符）。 */
    public record Seg(String type, String value) {
    }
}
