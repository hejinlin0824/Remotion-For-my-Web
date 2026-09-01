package com.wyf.factory.domain;

import java.util.Set;

/**
 * 任务状态机（spec §11 + T10 修复轮 M2 控制器裁决 + Ruling-17 + Ruling-18 + T27 确认闸）。
 *
 * <pre>
 * 正向链：QUEUED→EXTRACTING→GENERATING→REVIEWING→SPEAKING→QA→RENDERING→DONE
 * （Ruling-18：QA 前置为 still 预审，渲染只走一次——审帧帧本就由 qa_stills 从 composition
 * 直接 renderStill、不依赖成片，TTS 完成即具备 QA 全部输入；渲染成功即 DONE，渲染后无 QA 轮）
 * 确认闸（T27，仅 IMAGE 识图路径）：EXTRACTING→AWAITING_CONFIRM（识图真题停驻等用户三选一）→
 *   GENERATING（用户确认）/ EXTRACTING（修改转 TEXT 重审）/ FAILED（重审判废题）/ CANCELLED（取消）；
 *   TEXT 通道永不过闸。
 * 回退：QA→GENERATING（Ruling-17：QA 判负带 FAIL 清单回生成重做）、
 * REVIEWING→GENERATING（驳回重生成）；QA→RENDERING 复用为预审通过的正向对
 * 终态迁移：任意非终态→FAILED；QUEUED/EXTRACTING/GENERATING/REVIEWING/AWAITING_CONFIRM→CANCELLED
 * （SPEAKING 起阶段间不可取消）+ RENDERING/QA→CANCELLED（渲染/预审完成后检查点可取消，
 * 成片丢弃不入 artifacts——渲染进行中仍不打断，spec §11）；其余一律 false，含终态→任何、自身→自身。
 * </pre>
 */
public enum JobStatus {
    QUEUED, EXTRACTING, AWAITING_CONFIRM, GENERATING, REVIEWING, SPEAKING, RENDERING, QA, DONE, FAILED, CANCELLED;

    private static final Set<JobStatus> TERMINAL = Set.of(DONE, FAILED, CANCELLED);

    /** 是否终态（DONE/FAILED/CANCELLED）：终态不可再迁移。 */
    public boolean isTerminal() {
        return TERMINAL.contains(this);
    }

    /** 合法迁移判定：非法组合（含 null、自身、终态出发）一律 false。 */
    public static boolean canTransit(JobStatus from, JobStatus to) {
        if (from == null || to == null || from == to || from.isTerminal()) {
            return false;
        }
        return switch (from) {
            case QUEUED -> to == EXTRACTING || to == FAILED || to == CANCELLED;
            case EXTRACTING -> to == GENERATING || to == AWAITING_CONFIRM || to == FAILED || to == CANCELLED;   // T27：识图真题停驻等确认
            case AWAITING_CONFIRM -> to == GENERATING || to == EXTRACTING || to == FAILED || to == CANCELLED;   // T27：确认/修改重审/废题判死/取消
            case GENERATING -> to == REVIEWING || to == FAILED || to == CANCELLED;
            case REVIEWING -> to == SPEAKING || to == GENERATING || to == FAILED || to == CANCELLED;
            case SPEAKING -> to == QA || to == FAILED;   // Ruling-18：TTS 完成即进 still 预审
            case QA -> to == RENDERING || to == GENERATING || to == FAILED || to == CANCELLED;   // M2：预审完成后检查点；Ruling-17：判负回生成
            case RENDERING -> to == DONE || to == FAILED || to == CANCELLED;   // Ruling-18：渲染成功即 DONE（渲染后无 QA 轮）；M2：渲染完成后检查点
            default -> false; // 终态已在入口拦截
        };
    }
}
