package com.wyf.factory.domain;

import java.util.Set;

/**
 * 任务状态机（spec §11 + T10 修复轮 M2 控制器裁决）。
 *
 * <pre>
 * 正向链：QUEUED→EXTRACTING→GENERATING→REVIEWING→SPEAKING→RENDERING→QA→DONE
 * 回退：QA→RENDERING（审帧链异常回退重渲）、QA→GENERATING（Ruling-17：QA 判负带 FAIL 清单
 * 回生成重做）、REVIEWING→GENERATING（驳回重生成）
 * 终态迁移：任意非终态→FAILED；QUEUED/EXTRACTING/GENERATING/REVIEWING→CANCELLED
 * （SPEAKING 起阶段间不可取消）+ RENDERING/QA→CANCELLED（渲染/审帧完成后检查点可取消，
 * 成片丢弃不入 artifacts——渲染进行中仍不打断，spec §11）；其余一律 false，含终态→任何、自身→自身。
 * </pre>
 */
public enum JobStatus {
    QUEUED, EXTRACTING, GENERATING, REVIEWING, SPEAKING, RENDERING, QA, DONE, FAILED, CANCELLED;

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
            case EXTRACTING -> to == GENERATING || to == FAILED || to == CANCELLED;
            case GENERATING -> to == REVIEWING || to == FAILED || to == CANCELLED;
            case REVIEWING -> to == SPEAKING || to == GENERATING || to == FAILED || to == CANCELLED;
            case SPEAKING -> to == RENDERING || to == FAILED;
            case RENDERING -> to == QA || to == FAILED || to == CANCELLED;   // M2：渲染完成后检查点
            case QA -> to == DONE || to == RENDERING || to == GENERATING || to == FAILED || to == CANCELLED;   // M2：QA 完成后检查点；Ruling-17：判负回生成
            default -> false; // 终态已在入口拦截
        };
    }
}
