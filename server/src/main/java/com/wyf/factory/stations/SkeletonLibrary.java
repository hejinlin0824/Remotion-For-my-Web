package com.wyf.factory.stations;

import java.util.Map;

/**
 * L4 题型骨架规格库（T20b）：problemType → 四段条数 (min,max)。
 *
 * <p>保守起点，E2E 后事故驱动细化（内容侧哲学=事故驱动规则生长）；计算题 = {@link StationChecks}
 * 全局常量 = golden 规格（单源引用不抄数值，golden 逐字节稳定）。结构级差异（如证明题每步配
 * popup）不做机器强制，只进 Prompts.COORDINATOR 末尾「题型骨架段」；机器牙齿 =
 * {@link CoordinatorStation} checkCount 按题型查本表，违规走既有 problems 通道回传重做。</p>
 *
 * <p>四型规格均为 StationChecks 全局范围的子集：下游合并器/V1 用全局常量校验的语义不变
 * （题型规格更严只在 P0 拦截，不与下游范围冲突）。</p>
 */
final class SkeletonLibrary {

    /** 单段条数范围 [min,max]。 */
    record Range(int min, int max) {
    }

    /** 题型骨架规格：knowledge/steps/pitfalls/generalMethod 四段各自的条数范围。 */
    record Spec(Range knowledge, Range steps, Range pitfalls, Range generalMethod) {
    }

    /** 全局常量封装（StationChecks 单源；= 计算题规格 = problemType 非法/缺失时的回落值）。 */
    private static final Spec GLOBAL = new Spec(
            new Range(StationChecks.KNOWLEDGE_MIN, StationChecks.KNOWLEDGE_MAX),
            new Range(StationChecks.STEPS_MIN, StationChecks.STEPS_MAX),
            new Range(StationChecks.PITFALLS_MIN, StationChecks.PITFALLS_MAX),
            new Range(StationChecks.GENERAL_METHOD_MIN, StationChecks.GENERAL_METHOD_MAX));

    /** 保守起点（T20b 控制器裁定表）：基础题单知识点短链窄幅收窄；证明题逻辑链完整下限 4；应用题设参建模下限 4、推导链通常短于纯计算上限 8。 */
    private static final Map<String, Spec> SPECS = Map.of(
            "基础题", new Spec(new Range(2, 3), new Range(3, 6), new Range(1, 2), new Range(3, 4)),
            "计算题", GLOBAL,
            "证明题", new Spec(new Range(2, 4), new Range(4, 10), new Range(1, 3), new Range(3, 6)),
            "应用题", new Spec(new Range(2, 4), new Range(4, 8), new Range(1, 3), new Range(3, 6)));

    private SkeletonLibrary() {
    }

    /**
     * 按题型查骨架规格；problemType 缺失/非法时回落全局常量（=计算题规格）——题型门在
     * CoordinatorStation.validate 已记 problems，此处不二次记错（防级联，镜像 T18.1
     * counts.steps 非法范式）。
     */
    static Spec spec(String problemType) {
        return problemType == null ? GLOBAL : SPECS.getOrDefault(problemType, GLOBAL);
    }
}
