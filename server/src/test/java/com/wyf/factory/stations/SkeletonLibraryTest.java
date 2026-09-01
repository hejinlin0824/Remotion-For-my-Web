package com.wyf.factory.stations;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T20b 题型骨架规格表单测：四题型保守起点值逐字节对控制器裁定表；
 * 计算题 = StationChecks 全局常量（单源引用，golden 逐字节稳定）；
 * 非法/缺失回落全局（防级联回落路径的表级断言）。
 */
class SkeletonLibraryTest {

    private static SkeletonLibrary.Spec spec(int kMin, int kMax, int sMin, int sMax,
                                             int pMin, int pMax, int gMin, int gMax) {
        return new SkeletonLibrary.Spec(new SkeletonLibrary.Range(kMin, kMax), new SkeletonLibrary.Range(sMin, sMax),
                new SkeletonLibrary.Range(pMin, pMax), new SkeletonLibrary.Range(gMin, gMax));
    }

    @Test
    @DisplayName("四题型规格=控制器裁定表（T24b 事故 001db856 细化：基础题 knowledge 2-4/pitfalls 1-3；证明题下限4/应用题上限8 不动）")
    void specTableMatchesRuling() {
        assertThat(SkeletonLibrary.spec("基础题"))
                .isEqualTo(spec(2, 4, 3, 6, 1, 3, 3, 4));
        assertThat(SkeletonLibrary.spec("证明题"))
                .isEqualTo(spec(2, 4, 4, 10, 1, 3, 3, 6));
        assertThat(SkeletonLibrary.spec("应用题"))
                .isEqualTo(spec(2, 4, 4, 8, 1, 3, 3, 6));
    }

    @Test
    @DisplayName("计算题 = StationChecks 全局常量单源引用（golden 规格逐字节稳定，不抄数值）")
    void calcTypeReferencesStationChecksGlobals() {
        SkeletonLibrary.Spec calc = SkeletonLibrary.spec("计算题");
        assertThat(calc.knowledge().min()).isEqualTo(StationChecks.KNOWLEDGE_MIN);
        assertThat(calc.knowledge().max()).isEqualTo(StationChecks.KNOWLEDGE_MAX);
        assertThat(calc.steps().min()).isEqualTo(StationChecks.STEPS_MIN);
        assertThat(calc.steps().max()).isEqualTo(StationChecks.STEPS_MAX);
        assertThat(calc.pitfalls().min()).isEqualTo(StationChecks.PITFALLS_MIN);
        assertThat(calc.pitfalls().max()).isEqualTo(StationChecks.PITFALLS_MAX);
        assertThat(calc.generalMethod().min()).isEqualTo(StationChecks.GENERAL_METHOD_MIN);
        assertThat(calc.generalMethod().max()).isEqualTo(StationChecks.GENERAL_METHOD_MAX);
    }

    @Test
    @DisplayName("problemType 非法/缺失回落全局常量（=计算题规格，防级联不二次记错）")
    void invalidOrMissingTypeFallsBackToGlobal() {
        assertThat(SkeletonLibrary.spec("问答题")).isEqualTo(SkeletonLibrary.spec("计算题"));
        assertThat(SkeletonLibrary.spec(null)).isEqualTo(SkeletonLibrary.spec("计算题"));
        assertThat(SkeletonLibrary.spec("")).isEqualTo(SkeletonLibrary.spec("计算题"));
    }
}
