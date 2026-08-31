package com.wyf.factory.stations;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * GEN-P0 协调者产物：分片生成骨架（T18，stepRef 计划级分派自 T18.1）。协调者看得到全题，
 * 统一产出 ①题型 ②四段素材条数计划 ③每步 usesAnchor→题干行 id 指派（分片只领锚不造锚，
 * 锚点误用类错误单源化）④全部 scene 的 {id,act,component,stepRef} 清单（计划级步骤分派：
 * 哪张卡讲第几步由协调者定死，场景片照抄、不得自选）⑤术语表。
 * Java 侧校验（CoordinatorStation）：锚点行存在性、scene id 唯一、条数在
 * StationChecks 既有 MIN/MAX 范围内、stepRef 归属/序列/popup 紧跟（计划不变量），
 * 违反抛 retryable GlmException 整轮重试。
 *
 * <p>骨架只含计划不含正文；knowledge/steps/pitfalls/generalMethod 正文归 P2 素材片，
 * scenes 的 ttsText/props 归 P3..Pn 场景片（props.stepRef 必须照抄计划），problem 排版归
 * P1 题干片。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Skeleton(
        String problemType,
        Counts counts,
        List<String> anchors,
        List<ScenePlan> scenes,
        List<GlossaryTerm> glossary) {

    /** 四段素材条数计划（分片照此填正文，条数以骨架为准不得增减）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Counts(int knowledge, int steps, int pitfalls, int generalMethod) {
    }

    /**
     * 一个场景的计划位：id=s01 递增、act∈{2,3,4}、组件白名单内、stepRef=计划级步骤分派。
     * stepRef 可空（Integer）：仅 step-card / derivation-popup 携带（值 1..counts.steps，
     * 协调者单源指派「哪张卡讲第几步」）；其余组件必须为 null（NON_NULL 序列化时省略，
     * 场景片载荷与 prompt 契约「非 step 场景不加字段」一致）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScenePlan(String id, int act, String component, Integer stepRef) {
    }

    /** 关键术语统一叫法：后续分片凡提到该术语必须照 standard 用词（跨片一致性）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GlossaryTerm(String term, String standard) {
    }
}
