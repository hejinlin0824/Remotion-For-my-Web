package com.wyf.factory.render;

import com.wyf.factory.config.AppProperties;
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
 * RenderWorker（fake ProcessRunner 注入，零真调）：命令拼接（含/不含 --frames）、
 * exit!=0 → retryable RenderException 且消息含 stderr 摘要、timedOut → retryable、
 * 成功路径复制 ws/out/final.mp4 → artifacts/{jobId}/final.mp4。
 */
class RenderWorkerTest {

    @TempDir
    Path tempDir;

    private Path ws;
    private Path artifactsDir;
    private FakeRunner runner;
    private RenderWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        ws = tempDir.resolve("workspace/7");
        Files.createDirectories(ws);
        artifactsDir = tempDir.resolve("artifacts");
        runner = new FakeRunner();
        AppProperties props = new AppProperties();
        props.setArtifactsDir(artifactsDir.toString());
        props.getRender().setTimeoutMinutes(30);
        worker = new RenderWorker(runner, props);
    }

    @Test
    @DisplayName("全片渲染命令：cmd /c npx remotion render Lecture169 out/final.mp4，cwd=ws，timeout=30min")
    void fullRenderCommand() throws Exception {
        renderSuccess();

        worker.render(ws);

        assertThat(runner.calls).hasSize(1);
        FakeRunner.Call call = runner.calls.get(0);
        assertThat(call.cwd()).isEqualTo(ws);
        assertThat(call.command()).containsExactly(
                "cmd", "/c", "npx", "remotion", "render", "Lecture169", "out/final.mp4");
        assertThat(call.timeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(call.extraEnv()).isEmpty();
    }

    @Test
    @DisplayName("帧段渲染命令：末尾追加 --frames=0-30")
    void frameRangeRenderCommand() throws Exception {
        renderSuccess();

        worker.render(ws, "0-30");

        assertThat(runner.calls.get(0).command()).endsWith("--frames=0-30");
    }

    @Test
    @DisplayName("exit 1 → retryable RenderException，消息含 exit code 与 stderr 摘要")
    void nonZeroExit_retryableWithStderrDigest() {
        runner.renderResult = new ProcessResult(1, "", "Error: bundle 加载失败", false);

        assertThatThrownBy(() -> worker.render(ws))
                .isInstanceOf(RenderWorker.RenderException.class)
                .hasMessageContaining("Error: bundle 加载失败")
                .hasMessageContaining("1")
                .satisfies(e -> assertThat(((RenderWorker.RenderException) e).isRetryable()).isTrue());
    }

    @Test
    @DisplayName("timedOut → retryable RenderException「渲染超时」")
    void timeout_retryable() {
        runner.renderResult = ProcessResult.timedOut("", "");

        assertThatThrownBy(() -> worker.render(ws))
                .isInstanceOf(RenderWorker.RenderException.class)
                .hasMessageContaining("渲染超时")
                .satisfies(e -> assertThat(((RenderWorker.RenderException) e).isRetryable()).isTrue());
    }

    @Test
    @DisplayName("成功：ws/out/final.mp4 复制到 artifacts/{jobId}/final.mp4 并返回该路径")
    void success_copiesToArtifacts() throws Exception {
        renderSuccess();
        byte[] mp4 = "FAKE-MP4-BYTES".getBytes(StandardCharsets.UTF_8);
        Files.createDirectories(ws.resolve("out"));
        Files.write(ws.resolve("out/final.mp4"), mp4);

        Path produced = worker.render(ws);

        assertThat(produced).isEqualTo(artifactsDir.resolve("7").resolve("final.mp4"));
        assertThat(produced).hasBinaryContent(mp4);
    }

    private void renderSuccess() throws IOException {
        runner.renderResult = new ProcessResult(0, "", "", false);
        Files.createDirectories(ws.resolve("out"));
        Files.write(ws.resolve("out/final.mp4"), "FAKE-MP4".getBytes(StandardCharsets.UTF_8));
    }

    /** 单一可编程序列 fake：render 调用返回 renderResult。 */
    static final class FakeRunner implements ProcessRunner {

        record Call(Path cwd, List<String> command, Map<String, String> extraEnv, Duration timeout) {
        }

        final List<Call> calls = new ArrayList<>();
        ProcessResult renderResult = new ProcessResult(0, "", "", false);

        @Override
        public ProcessResult run(Path cwd, List<String> command, Map<String, String> extraEnv, Duration timeout) {
            calls.add(new Call(cwd, List.copyOf(command), Map.copyOf(extraEnv), timeout));
            return renderResult;
        }
    }
}
