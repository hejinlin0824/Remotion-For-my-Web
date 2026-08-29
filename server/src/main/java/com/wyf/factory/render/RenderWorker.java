package com.wyf.factory.render;

import com.wyf.factory.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 渲染工位：cwd=任务工作区 spawn {@code cmd /c npx remotion render Lecture169 out/final.mp4}
 * （16:9 唯一画幅，Global Constraint 2；timeout=app.render.timeoutMinutes）。可选帧段
 * frameRange（如 "0-30"）追加 {@code --frames=}。超时 → retryable RenderException「渲染超时」；
 * exit!=0 → retryable RenderException（stderr 截断 2000 字符）；成功把 ws/out/final.mp4
 * 复制到 artifacts/{jobId}/final.mp4 并返回（jobId 即工作区目录名）。
 */
@Component
public class RenderWorker {

    private static final int STDERR_DIGEST_CHARS = 2000;

    /** 渲染失败。retryable=true=瞬态（超时/进程错误），编排方可整段重试。 */
    public static final class RenderException extends RuntimeException {

        private final boolean retryable;

        public RenderException(String message, boolean retryable) {
            super(message);
            this.retryable = retryable;
        }

        public boolean isRetryable() {
            return retryable;
        }
    }

    private final ProcessRunner runner;
    private final AppProperties props;

    public RenderWorker(ProcessRunner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
    }

    /** 全片渲染。 */
    public Path render(Path ws) throws InterruptedException {
        return render(ws, null);
    }

    /** 渲染；frameRange 非 null 时追加 {@code --frames=<frameRange>}（slow 短渲用）。 */
    public Path render(Path ws, String frameRange) throws InterruptedException {
        List<String> command = new ArrayList<>(List.of(
                "cmd", "/c", "npx", "remotion", "render", "Lecture169", "out/final.mp4"));
        if (frameRange != null) {
            command.add("--frames=" + frameRange);
        }
        ProcessResult result = runner.run(ws, command, Map.of(),
                Duration.ofMinutes(props.getRender().getTimeoutMinutes()));
        if (result.timedOut()) {
            throw new RenderException("渲染超时（>" + props.getRender().getTimeoutMinutes() + " 分钟）", true);
        }
        if (result.exitCode() != 0) {
            throw new RenderException("渲染失败 exit=" + result.exitCode() + "："
                    + digest(result.stderr(), result.stdout()), true);
        }
        Path produced = ws.resolve("out/final.mp4");
        if (!Files.isRegularFile(produced)) {
            throw new RenderException("渲染 exit=0 但产物缺失：" + produced, true);
        }
        return copyToArtifacts(ws, produced);
    }

    /** 成片归档：artifacts/{jobId}/final.mp4（jobId = 工作区目录名）。 */
    private Path copyToArtifacts(Path ws, Path produced) {
        Path dest = Path.of(props.getArtifactsDir())
                .resolve(ws.getFileName().toString())
                .resolve("final.mp4");
        try {
            Files.createDirectories(dest.getParent());
            Files.copy(produced, dest, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("成片复制失败：" + produced + " → " + dest, e);
        }
        return dest;
    }

    /** stderr 为主（remotion 错误走 stderr）、stdout 兜底，截断 2000 字符。 */
    private static String digest(String stderr, String stdout) {
        String text = stderr != null && !stderr.isBlank() ? stderr : stdout;
        String stripped = text == null ? "" : text.strip();
        return stripped.length() <= STDERR_DIGEST_CHARS
                ? stripped
                : stripped.substring(0, STDERR_DIGEST_CHARS) + "…";
    }
}
