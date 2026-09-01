package com.wyf.factory.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobStatusTest {

    /**
     * 合法迁移对全集（spec §11 + 计划 Task 2 + T10 修复轮 M2 + Ruling-17 + Ruling-18 + T27）：
     * 全表 11×11 中仅这 27 对为 true。T27 新增：EXTRACTING→AWAITING_CONFIRM（识图确认闸，
     * 识图真题停驻等用户）与 AWAITING_CONFIRM→GENERATING（确认）/→EXTRACTING（修改重审）/
     * →FAILED（废题）/→CANCELLED（取消）。
     */
    private static final Set<List<JobStatus>> LEGAL = Set.of(
            // 正向链 QUEUED→EXTRACTING→GENERATING→REVIEWING→SPEAKING→QA→RENDERING→DONE
            //（Ruling-18：QA 前置为 still 预审，渲染只走一次——渲染成功即 DONE，渲染后无 QA 轮）
            List.of(JobStatus.QUEUED, JobStatus.EXTRACTING),
            List.of(JobStatus.EXTRACTING, JobStatus.GENERATING),
            // T27 确认闸：EXTRACTING 识图真题 → AWAITING_CONFIRM 停驻等用户三选一
            List.of(JobStatus.EXTRACTING, JobStatus.AWAITING_CONFIRM),
            List.of(JobStatus.GENERATING, JobStatus.REVIEWING),
            List.of(JobStatus.REVIEWING, JobStatus.SPEAKING),
            List.of(JobStatus.SPEAKING, JobStatus.QA),
            List.of(JobStatus.QA, JobStatus.RENDERING),
            List.of(JobStatus.RENDERING, JobStatus.DONE),
            // 回退：REVIEWING 驳回 / Ruling-17 QA 判负 → 带清单回生成重做（QA→RENDERING 复用为预审通过的正向对）
            List.of(JobStatus.QA, JobStatus.GENERATING),
            List.of(JobStatus.REVIEWING, JobStatus.GENERATING),
            // T27 确认闸三选一：确认→GENERATING / 修改→EXTRACTING 重审（转 TEXT，非废题直接 GENERATING 不再过闸）
            List.of(JobStatus.AWAITING_CONFIRM, JobStatus.GENERATING),
            List.of(JobStatus.AWAITING_CONFIRM, JobStatus.EXTRACTING),
            // 终态迁移：任意非终态 → FAILED
            List.of(JobStatus.QUEUED, JobStatus.FAILED),
            List.of(JobStatus.EXTRACTING, JobStatus.FAILED),
            List.of(JobStatus.GENERATING, JobStatus.FAILED),
            List.of(JobStatus.REVIEWING, JobStatus.FAILED),
            List.of(JobStatus.SPEAKING, JobStatus.FAILED),
            List.of(JobStatus.RENDERING, JobStatus.FAILED),
            List.of(JobStatus.QA, JobStatus.FAILED),
            List.of(JobStatus.AWAITING_CONFIRM, JobStatus.FAILED),   // T27：废题重审判死
            // 取消：SPEAKING 起不可取消（spec §11 取消语义）；
            // RENDERING/QA→CANCELLED 为 T10 修复轮 M2（阶段完成检查点可取消，成片丢弃）
            List.of(JobStatus.QUEUED, JobStatus.CANCELLED),
            List.of(JobStatus.EXTRACTING, JobStatus.CANCELLED),
            List.of(JobStatus.GENERATING, JobStatus.CANCELLED),
            List.of(JobStatus.REVIEWING, JobStatus.CANCELLED),
            List.of(JobStatus.RENDERING, JobStatus.CANCELLED),
            List.of(JobStatus.QA, JobStatus.CANCELLED),
            List.of(JobStatus.AWAITING_CONFIRM, JobStatus.CANCELLED));   // T27：待确认态可取消

    @Test
    @DisplayName("11×11 迁移全表：合法 27 对为 true，其余 94 对一律 false")
    void canTransit_fullMatrix_onlyLegalPairsPass() {
        assertThat(JobStatus.values()).hasSize(11);

        int legalCount = 0;
        for (JobStatus from : JobStatus.values()) {
            for (JobStatus to : JobStatus.values()) {
                boolean expected = LEGAL.contains(List.of(from, to));
                boolean actual = JobStatus.canTransit(from, to);
                if (actual) {
                    legalCount++;
                }
                assertThat(actual)
                        .as("%s -> %s 应为 %s", from, to, expected)
                        .isEqualTo(expected);
            }
        }
        assertThat(legalCount).as("合法迁移总数").isEqualTo(LEGAL.size());
    }

    @Test
    @DisplayName("Ruling-18：QA→DONE 不再合法（渲染后无 QA 轮）；SPEAKING→QA 与 RENDERING→DONE 为新正向对")
    void canTransit_ruling18_pairs() {
        // 负例：QA 判负回 GENERATING / 预审过进 RENDERING，唯独不再直通 DONE
        assertThat(JobStatus.canTransit(JobStatus.QA, JobStatus.DONE)).isFalse();
        // 新增正向对
        assertThat(JobStatus.canTransit(JobStatus.SPEAKING, JobStatus.QA)).isTrue();
        assertThat(JobStatus.canTransit(JobStatus.RENDERING, JobStatus.DONE)).isTrue();
        // 既有保留
        assertThat(JobStatus.canTransit(JobStatus.QA, JobStatus.RENDERING)).isTrue();
        assertThat(JobStatus.canTransit(JobStatus.QA, JobStatus.GENERATING)).isTrue();
    }

    @Test
    @DisplayName("T27 确认闸：EXTRACTING→AWAITING_CONFIRM 与 AWAITING_CONFIRM 三选一（确认/修改/取消）+ 废题 FAILED 全为合法对；AWAITING_CONFIRM 不直通 DONE/SPEAKING")
    void canTransit_t27_awaitingConfirmGatePairs() {
        assertThat(JobStatus.canTransit(JobStatus.EXTRACTING, JobStatus.AWAITING_CONFIRM)).isTrue();
        assertThat(JobStatus.canTransit(JobStatus.AWAITING_CONFIRM, JobStatus.GENERATING)).isTrue();
        assertThat(JobStatus.canTransit(JobStatus.AWAITING_CONFIRM, JobStatus.EXTRACTING)).isTrue();
        assertThat(JobStatus.canTransit(JobStatus.AWAITING_CONFIRM, JobStatus.FAILED)).isTrue();
        assertThat(JobStatus.canTransit(JobStatus.AWAITING_CONFIRM, JobStatus.CANCELLED)).isTrue();
        // 负例：待确认态不得跳过重审/生成直达下游
        assertThat(JobStatus.canTransit(JobStatus.AWAITING_CONFIRM, JobStatus.DONE)).isFalse();
        assertThat(JobStatus.canTransit(JobStatus.AWAITING_CONFIRM, JobStatus.REVIEWING)).isFalse();
        assertThat(JobStatus.canTransit(JobStatus.AWAITING_CONFIRM, JobStatus.SPEAKING)).isFalse();
        assertThat(JobStatus.canTransit(JobStatus.AWAITING_CONFIRM, JobStatus.RENDERING)).isFalse();
        assertThat(JobStatus.canTransit(JobStatus.AWAITING_CONFIRM, JobStatus.AWAITING_CONFIRM)).isFalse();
    }

    @Test
    @DisplayName("enterStage：QA→DONE 抛 IllegalStateException（状态机语义变更的显式负例）")
    void enterStage_qaToDone_throws() {
        Job job = jobAt(JobStatus.QA);
        assertThatThrownBy(() -> job.enterStage(JobStatus.DONE, "渲染后直通 DONE（Ruling-18 后非法）"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QA")
                .hasMessageContaining("DONE");
        assertThat(job.getStatus()).isEqualTo(JobStatus.QA);
    }

    @Test
    @DisplayName("终态（DONE/FAILED/CANCELLED）不可迁移到任何状态；自身→自身一律 false")
    void canTransit_terminalAndSelfTransitions_areFalse() {
        for (JobStatus terminal : List.of(JobStatus.DONE, JobStatus.FAILED, JobStatus.CANCELLED)) {
            for (JobStatus to : JobStatus.values()) {
                assertThat(JobStatus.canTransit(terminal, to))
                        .as("终态 %s -> %s 必须为 false", terminal, to)
                        .isFalse();
            }
        }
        for (JobStatus s : JobStatus.values()) {
            assertThat(JobStatus.canTransit(s, s))
                    .as("自身迁移 %s -> %s 必须为 false", s, s)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("enterStage：非法迁移抛 IllegalStateException，状态与历史不被污染")
    void enterStage_illegalTransition_throwsAndLeavesStateUntouched() {
        Job job = jobAt(JobStatus.QUEUED);

        assertThatThrownBy(() -> job.enterStage(JobStatus.GENERATING, "试图跳过审题"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("QUEUED")
                .hasMessageContaining("GENERATING");

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(job.getStageHistory()).hasSize(1); // 仅入队一条
    }

    @Test
    @DisplayName("enterStage：终态不可再进入任何阶段")
    void enterStage_fromTerminal_throws() {
        assertThatThrownBy(() -> jobAt(JobStatus.DONE).enterStage(JobStatus.RENDERING, "复渲"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> jobAt(JobStatus.CANCELLED).enterStage(JobStatus.QUEUED, "复活"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("enterStage：合法迁移同步 status/stage/updatedAt 并追加一条 ENTER 历史")
    void enterStage_legalTransition_syncsStatusStageHistory() {
        Job job = jobAt(JobStatus.QUEUED);
        int historyBefore = job.getStageHistory().size();

        job.enterStage(JobStatus.EXTRACTING, "审题开始");

        assertThat(job.getStatus()).isEqualTo(JobStatus.EXTRACTING);
        assertThat(job.getStage()).isEqualTo("EXTRACTING");
        assertThat(job.getStageHistory()).hasSize(historyBefore + 1);
        StageHistoryEntry entry = job.getStageHistory().get(job.getStageHistory().size() - 1);
        assertThat(entry.getStage()).isEqualTo("EXTRACTING");
        assertThat(entry.getState()).isEqualTo("ENTER");
        assertThat(entry.getNote()).isEqualTo("审题开始");
        assertThat(entry.getAt()).isNotNull();
        assertThat(job.getUpdatedAt()).isNotNull();
    }

    @Test
    @DisplayName("isTerminal：DONE/FAILED/CANCELLED 为终态，其余非终态")
    void isTerminal_marksOnlyThreeFinalStates() {
        for (JobStatus s : JobStatus.values()) {
            boolean expected = s == JobStatus.DONE || s == JobStatus.FAILED || s == JobStatus.CANCELLED;
            assertThat(s.isTerminal()).as("%s.isTerminal()", s).isEqualTo(expected);
        }
    }

    private static Job jobAt(JobStatus status) {
        Job job = new Job();
        job.setId(UUID.randomUUID().toString());
        job.setStatus(status);
        return job;
    }
}
