package com.wyf.factory.render;

import com.wyf.factory.config.Secrets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QaFrameCheck（fake ProcessRunner 注入，零真调、零 GLM 成本）：
 * pick_frames stdout 解析（totalFrames 行跳过/tab 分隔/前缀行名）、逐帧 still 命令、
 * qa_glm key 只进子进程 env、exit=1 + report.md FAIL 行收集、无 FAIL 行 → ["qa_glm exit=N"]、
 * 空帧清单防呆。
 */
class QaFrameCheckTest {

    private static final String KEY = "test-key-do-not-leak";

    @TempDir
    Path tempDir;

    private Path ws;
    private FakeRunner runner;
    private QaFrameCheck qa;

    @BeforeEach
    void setUp() throws IOException {
        ws = tempDir.resolve("workspace/7");
        Files.createDirectories(ws);
        runner = new FakeRunner();
        qa = new QaFrameCheck(runner, new Secrets(name -> KEY, tempDir.resolve("absent.yml")));
    }

    @Test
    @DisplayName("解析+全过：totalFrames 行跳过；逐帧 still；qa_glm exit=0 → pass=true")
    void parsesFrames_allPass() throws Exception {
        runner.pickFramesStdout = "totalFrames = 5334\nact1-中段\t78\ns-s01-problem-card\t313\n";
        runner.qaResult = new ProcessResult(0, "", "", false);

        QaFrameCheck.QaResult result = qa.check(ws);

        assertThat(result.pass()).isTrue();
        assertThat(result.fails()).isEmpty();
        assertThat(result.framesChecked()).isEqualTo(2);
        assertThat(runner.calls).hasSize(4); // pick_frames + 2 still + qa_glm
        assertThat(runner.calls.get(0).command()).containsExactly(
                "cmd", "/c", "node", "scripts/pick_frames.mjs");
        assertThat(runner.calls.get(0).cwd()).isEqualTo(ws);
        assertThat(runner.calls.get(1).command()).containsExactly(
                "cmd", "/c", "npx", "remotion", "still", "Lecture169",
                "out/qa/act1-中段.png", "--frame=78");
        assertThat(runner.calls.get(2).command()).containsExactly(
                "cmd", "/c", "npx", "remotion", "still", "Lecture169",
                "out/qa/s-s01-problem-card.png", "--frame=313");
        assertThat(runner.calls.get(3).command()).containsExactly(
                "cmd", "/c", "python", "scripts/qa_glm.py");
    }

    @Test
    @DisplayName("GLM key 只进 qa_glm 子进程 extraEnv（ZHIPU_API_KEY），不落命令行与日志流")
    void glmKey_onlyIntoSubprocessEnv() throws Exception {
        runner.pickFramesStdout = "totalFrames = 100\ns-s01-problem-card\t30\n";
        runner.qaResult = new ProcessResult(0, "", "", false);

        qa.check(ws);

        FakeRunner.Call qaCall = runner.calls.stream()
                .filter(call -> String.join(" ", call.command()).contains("qa_glm.py"))
                .findFirst().orElseThrow();
        assertThat(qaCall.extraEnv()).containsOnlyKeys("ZHIPU_API_KEY");
        assertThat(qaCall.extraEnv().get("ZHIPU_API_KEY")).isEqualTo(KEY);
        for (FakeRunner.Call call : runner.calls) {
            assertThat(String.join(" ", call.command())).doesNotContain(KEY);
        }
    }

    @Test
    @DisplayName("qa_glm exit=1 + report.md 含 FAIL 行 → fails 收集 FAIL 行、pass=false")
    void qaFails_reportLinesCollected() throws Exception {
        runner.pickFramesStdout = "totalFrames = 100\ns-s01-problem-card\t30\n";
        runner.qaResult = new ProcessResult(1, "", "", false);
        Path qaDir = ws.resolve("out/qa");
        Files.createDirectories(qaDir);
        Files.writeString(qaDir.resolve("report.md"), """
                # GLM 审帧报告 — glm-5.3-flash

                ## s-s01-problem-card.png

                1) 无重叠。2) 无乱码。3) 对比度正常。4) 公式正常。
                FAIL（公式 LaTeX 源码裸露）
                """, StandardCharsets.UTF_8);

        QaFrameCheck.QaResult result = qa.check(ws);

        assertThat(result.pass()).isFalse();
        assertThat(result.fails()).containsExactly("FAIL（公式 LaTeX 源码裸露）");
        assertThat(result.framesChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("qa_glm exit=1 但无 FAIL 行 → fails=[qa_glm exit=1]")
    void qaFails_noFailLines() throws Exception {
        runner.pickFramesStdout = "totalFrames = 100\ns-s01-problem-card\t30\n";
        runner.qaResult = new ProcessResult(1, "[fatal] 未找到 GLM Key", "", false);

        QaFrameCheck.QaResult result = qa.check(ws);

        assertThat(result.pass()).isFalse();
        assertThat(result.fails()).containsExactly("qa_glm exit=1");
    }

    @Test
    @DisplayName("帧清单为空（只有 totalFrames 行）→ 防呆 QaResult(false, [pick_frames 无帧行], 0)，不跑 still/qa_glm")
    void emptyFrameList_foolProof() throws Exception {
        runner.pickFramesStdout = "totalFrames = 5334\n\n";

        QaFrameCheck.QaResult result = qa.check(ws);

        assertThat(result.pass()).isFalse();
        assertThat(result.fails()).containsExactly("pick_frames 无帧行");
        assertThat(result.framesChecked()).isZero();
        assertThat(runner.calls).hasSize(1); // 只有 pick_frames
    }

    @Test
    @DisplayName("still 失败（exit!=0）→ 抛 retryable RenderException，不再跑后续帧与 qa_glm")
    void stillFailure_raisesRenderException() throws Exception {
        runner.pickFramesStdout = "totalFrames = 100\ns-s01-problem-card\t30\ns-s02-knowledge-card\t90\n";
        runner.stillResult = new ProcessResult(1, "", "Error: still failed", false);

        assertThatThrownBy(() -> qa.check(ws))
                .isInstanceOf(RenderWorker.RenderException.class)
                .hasMessageContaining("still")
                .satisfies(e -> assertThat(((RenderWorker.RenderException) e).isRetryable()).isTrue());
        assertThat(runner.calls).hasSize(2); // pick_frames + 首帧失败即止
    }

    /** 按命令段路由的 fake：pick_frames 输出可编程，still/qa_glm 结果可编程。 */
    static final class FakeRunner implements ProcessRunner {

        record Call(Path cwd, List<String> command, Map<String, String> extraEnv, Duration timeout) {
        }

        final List<Call> calls = new ArrayList<>();
        String pickFramesStdout = "";
        ProcessResult stillResult = new ProcessResult(0, "", "", false);
        ProcessResult qaResult = new ProcessResult(0, "", "", false);

        @Override
        public ProcessResult run(Path cwd, List<String> command, Map<String, String> extraEnv, Duration timeout) {
            calls.add(new Call(cwd, List.copyOf(command), Map.copyOf(extraEnv), timeout));
            String joined = String.join(" ", command);
            if (joined.contains("pick_frames.mjs")) {
                return new ProcessResult(0, pickFramesStdout, "", false);
            }
            if (joined.contains("still")) {
                return stillResult;
            }
            if (joined.contains("qa_glm.py")) {
                return qaResult;
            }
            throw new IllegalStateException("fake 未编排的命令：" + joined);
        }
    }
}
