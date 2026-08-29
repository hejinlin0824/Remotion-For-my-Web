package com.wyf.factory.render;

import com.wyf.factory.config.Secrets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * pick_frames stdout 解析（totalFrames 行跳过/tab 分隔/前缀行名）、单次 qa_stills 批量调用
 * （manifest 内容=解析结果、非逐帧 N 次 spawn）、qa_stills stdout 失败行收集、
 * qa_glm key 只进子进程 env、exit=1 + report.md FAIL 行收集、独立 ERROR 行 → [error] 前缀收集
 * （FAIL/ERROR 混合都收）、无 FAIL/ERROR 行 → ["qa_glm exit=N"] 兜底、空帧清单防呆。
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
    @DisplayName("解析+全过：totalFrames 行跳过；单次 qa_stills 批量调用（manifest=解析结果）；qa_glm exit=0 → pass=true")
    void parsesFrames_allPass() throws Exception {
        runner.pickFramesStdout = "totalFrames = 5334\nact1-中段\t78\ns-s01-problem-card\t313\n";
        runner.qaResult = new ProcessResult(0, "", "", false);

        QaFrameCheck.QaResult result = qa.check(ws);

        assertThat(result.pass()).isTrue();
        assertThat(result.fails()).isEmpty();
        assertThat(result.framesChecked()).isEqualTo(2);
        assertThat(runner.calls).hasSize(3); // pick_frames + qa_stills 批量 + qa_glm
        assertThat(runner.calls.get(0).command()).containsExactly(
                "cmd", "/c", "node", "scripts/pick_frames.mjs");
        assertThat(runner.calls.get(0).cwd()).isEqualTo(ws);
        assertThat(runner.calls.get(1).command()).containsExactly(
                "cmd", "/c", "node", "scripts/qa_stills.mjs", "Lecture169",
                "out/qa/frames.json", "out/qa");
        assertThat(runner.calls.get(1).timeout()).isEqualTo(Duration.ofMinutes(5));
        // manifest 内容 = pick_frames 解析结果（帧名/帧号逐行对应）
        assertThat(runner.manifestAtCall).isEqualTo(
                "[{\"name\":\"act1-中段\",\"frame\":78},{\"name\":\"s-s01-problem-card\",\"frame\":313}]");
        assertThat(ws.resolve("out/qa/frames.json")).isRegularFile();
        assertThat(runner.calls.get(2).command()).containsExactly(
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
    @DisplayName("qa_glm exit=1 + report.md 含独立 ERROR 行（单帧最终失败）→ fails 以 [error] 前缀收集该行")
    void qaFails_errorLinesCollectedWithErrorPrefix() throws Exception {
        runner.pickFramesStdout = "totalFrames = 100\ns-s01-problem-card\t30\n";
        runner.qaResult = new ProcessResult(1, "", "", false);
        Path qaDir = ws.resolve("out/qa");
        Files.createDirectories(qaDir);
        Files.writeString(qaDir.resolve("report.md"), """
                # GLM 审帧报告 — glm-5.3-flash

                ## s-s01-problem-card.png

                [error] 重试耗尽（ConnectTimeout: connection reset by peer）

                ERROR s-s01-problem-card.png\t重试耗尽（ConnectTimeout: connection reset by peer）
                """, StandardCharsets.UTF_8);

        QaFrameCheck.QaResult result = qa.check(ws);

        assertThat(result.pass()).isFalse();
        assertThat(result.fails()).containsExactly(
                "[error] s-s01-problem-card.png\t重试耗尽（ConnectTimeout: connection reset by peer）");
        assertThat(result.framesChecked()).isEqualTo(1);
    }

    @Test
    @DisplayName("qa_glm exit=1 + report.md FAIL 行与 ERROR 行混合 → 两者按报告行序都收集")
    void qaFails_mixedFailAndErrorLines() throws Exception {
        runner.pickFramesStdout = "totalFrames = 200\ns-s01-problem-card\t30\ns-s02-knowledge-card\t90\n";
        runner.qaResult = new ProcessResult(1, "", "", false);
        Path qaDir = ws.resolve("out/qa");
        Files.createDirectories(qaDir);
        Files.writeString(qaDir.resolve("report.md"), """
                # GLM 审帧报告 — glm-5.3-flash

                ## s-s01-problem-card.png

                1) 重叠。2) 无乱码。
                FAIL（文字卡片重叠溢出画面边界）

                ## s-s02-knowledge-card.png

                [error] 重试耗尽（HTTP 429）

                ERROR s-s02-knowledge-card.png\t重试耗尽（HTTP 429）
                """, StandardCharsets.UTF_8);

        QaFrameCheck.QaResult result = qa.check(ws);

        assertThat(result.pass()).isFalse();
        assertThat(result.fails()).containsExactly(
                "FAIL（文字卡片重叠溢出画面边界）",
                "[error] s-s02-knowledge-card.png\t重试耗尽（HTTP 429）");
    }

    @Test
    @DisplayName("qa_glm exit=1 但 report.md 无 FAIL/ERROR 行（PASS-only）→ 兜底 fails=[qa_glm exit=1]")
    void qaFails_noFailLines() throws Exception {
        runner.pickFramesStdout = "totalFrames = 100\ns-s01-problem-card\t30\n";
        runner.qaResult = new ProcessResult(1, "[fatal] 未找到 GLM Key", "", false);
        Path qaDir = ws.resolve("out/qa");
        Files.createDirectories(qaDir);
        Files.writeString(qaDir.resolve("report.md"), """
                # GLM 审帧报告 — glm-5.3-flash

                ## s-s01-problem-card.png

                1) 无重叠。2) 无乱码。
                PASS
                """, StandardCharsets.UTF_8);

        QaFrameCheck.QaResult result = qa.check(ws);

        assertThat(result.pass()).isFalse();
        assertThat(result.fails()).containsExactly("qa_glm exit=1");
    }

    @Test
    @DisplayName("帧清单为空（只有 totalFrames 行）→ 防呆 QaResult(false, [pick_frames 无帧行], 0)，不跑 stills/qa_glm")
    void emptyFrameList_foolProof() throws Exception {
        runner.pickFramesStdout = "totalFrames = 5334\n\n";

        QaFrameCheck.QaResult result = qa.check(ws);

        assertThat(result.pass()).isFalse();
        assertThat(result.fails()).containsExactly("pick_frames 无帧行");
        assertThat(result.framesChecked()).isZero();
        assertThat(runner.calls).hasSize(1); // 只有 pick_frames
    }

    @Test
    @DisplayName("qa_stills 失败（exit!=0 + stdout 失败行）→ 抛 retryable RenderException 含失败行，不再跑 qa_glm")
    void stillsFailure_raisesRenderExceptionWithFailRows() throws Exception {
        runner.pickFramesStdout = "totalFrames = 100\ns-s01-problem-card\t30\ns-s02-knowledge-card\t90\n";
        runner.stillsResult = new ProcessResult(1,
                "s-s01-problem-card\t30\tok\ns-s02-knowledge-card\t90\tfail\n",
                "Error: renderStill failed", false);

        assertThatThrownBy(() -> qa.check(ws))
                .isInstanceOf(RenderWorker.RenderException.class)
                .hasMessageContaining("still")
                .hasMessageContaining("s-s02-knowledge-card\t90\tfail")
                .hasMessageNotContaining("s-s01-problem-card")
                .satisfies(e -> assertThat(((RenderWorker.RenderException) e).isRetryable()).isTrue());
        assertThat(runner.calls).hasSize(2); // pick_frames + qa_stills 失败即止
    }

    @Test
    @DisplayName("qa_stills 超时 → 抛 retryable RenderException（超时归因），不再跑 qa_glm")
    void stillsTimeout_raisesRenderException() throws Exception {
        runner.pickFramesStdout = "totalFrames = 100\ns-s01-problem-card\t30\n";
        runner.stillsResult = ProcessResult.timedOut("", "");

        assertThatThrownBy(() -> qa.check(ws))
                .isInstanceOf(RenderWorker.RenderException.class)
                .hasMessageContaining("qa_stills 超时")
                .satisfies(e -> assertThat(((RenderWorker.RenderException) e).isRetryable()).isTrue());
        assertThat(runner.calls).hasSize(2);
    }

    /** 按命令段路由的 fake：pick_frames 输出可编程，qa_stills 结果可编程并捕获 manifest 落盘内容，qa_glm 结果可编程。 */
    static final class FakeRunner implements ProcessRunner {

        record Call(Path cwd, List<String> command, Map<String, String> extraEnv, Duration timeout) {
        }

        final List<Call> calls = new ArrayList<>();
        String pickFramesStdout = "";
        ProcessResult stillsResult = new ProcessResult(0, "", "", false);
        ProcessResult qaResult = new ProcessResult(0, "", "", false);
        String manifestAtCall;

        @Override
        public ProcessResult run(Path cwd, List<String> command, Map<String, String> extraEnv, Duration timeout) {
            calls.add(new Call(cwd, List.copyOf(command), Map.copyOf(extraEnv), timeout));
            String joined = String.join(" ", command);
            if (joined.contains("pick_frames.mjs")) {
                return new ProcessResult(0, pickFramesStdout, "", false);
            }
            if (joined.contains("qa_stills.mjs")) {
                String manifestArg = command.stream().filter(a -> a.endsWith(".json")).findFirst()
                        .orElseThrow(() -> new IllegalStateException("qa_stills 调用缺 manifest 参数"));
                try {
                    manifestAtCall = Files.readString(cwd.resolve(manifestArg), StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new UncheckedIOException("manifest 读取失败：" + manifestArg, e);
                }
                return stillsResult;
            }
            if (joined.contains("qa_glm.py")) {
                return qaResult;
            }
            throw new IllegalStateException("fake 未编排的命令：" + joined);
        }
    }
}
