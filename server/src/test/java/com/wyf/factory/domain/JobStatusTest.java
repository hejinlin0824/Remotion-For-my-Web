package com.wyf.factory.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobStatusTest {

    /** 合法迁移对全集（spec §11 + 计划 Task 2）：全表 10×10 中仅这 20 对为 true。 */
    private static final Set<List<JobStatus>> LEGAL = Set.of(
            // 正向链 QUEUED→EXTRACTING→GENERATING→REVIEWING→SPEAKING→RENDERING→QA→DONE
            List.of(JobStatus.QUEUED, JobStatus.EXTRACTING),
            List.of(JobStatus.EXTRACTING, JobStatus.GENERATING),
            List.of(JobStatus.GENERATING, JobStatus.REVIEWING),
            List.of(JobStatus.REVIEWING, JobStatus.SPEAKING),
            List.of(JobStatus.SPEAKING, JobStatus.RENDERING),
            List.of(JobStatus.RENDERING, JobStatus.QA),
            List.of(JobStatus.QA, JobStatus.DONE),
            // 回退：QA 轮次重渲染；驳回重生成
            List.of(JobStatus.QA, JobStatus.RENDERING),
            List.of(JobStatus.REVIEWING, JobStatus.GENERATING),
            // 终态迁移：任意非终态 → FAILED
            List.of(JobStatus.QUEUED, JobStatus.FAILED),
            List.of(JobStatus.EXTRACTING, JobStatus.FAILED),
            List.of(JobStatus.GENERATING, JobStatus.FAILED),
            List.of(JobStatus.REVIEWING, JobStatus.FAILED),
            List.of(JobStatus.SPEAKING, JobStatus.FAILED),
            List.of(JobStatus.RENDERING, JobStatus.FAILED),
            List.of(JobStatus.QA, JobStatus.FAILED),
            // 取消：SPEAKING 起不可取消（spec §11 取消语义）
            List.of(JobStatus.QUEUED, JobStatus.CANCELLED),
            List.of(JobStatus.EXTRACTING, JobStatus.CANCELLED),
            List.of(JobStatus.GENERATING, JobStatus.CANCELLED),
            List.of(JobStatus.REVIEWING, JobStatus.CANCELLED));

    @Test
    @DisplayName("10×10 迁移全表：合法 20 对为 true，其余 80 对一律 false")
    void canTransit_fullMatrix_onlyLegalPairsPass() {
        assertThat(JobStatus.values()).hasSize(10);

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
