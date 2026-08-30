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
 * frameRange（如 "0-30"）追加 {@code --frames=}；可选渲染档位 resolution（T17）：
 * "1080p"（默认）原生命令零追加（零漂移门槛），"720p" 追加 {@code --scale=0.6666666666666666}
 * （2/3 的 IEEE754 double 与 1920/1080 相乘恰得整数 1280/720，Remotion 4.0.518
 * validateScale 只限正数 ≤16 不限整数，等比缩放非截戳，模板/composition 零改动）。
 * 超时 → retryable RenderException「渲染超时」；exit!=0 → retryable RenderException
 * （stderr 截断 2000 字符）；成功把 ws/out/final.mp4 复制到 artifacts/{jobId}/final.mp4
 * 并返回（jobId 即工作区目录名）。
 */
@Component
public class RenderWorker {

    private static final int STDERR_DIGEST_CHARS = 2000;

    /** 渲染档位：1080p 原生母版（缺省）。 */
    public static final String RESOLUTION_1080P = "1080p";
    /** 渲染档位：720p 等比（--scale=2/3 → 1280×720）。 */
    public static final String RESOLUTION_720P = "720p";

    /**
     * 720p 的 scale 值（2/3 的完整 double 字面量）：1920×(2/3)=1280、1080×(2/3)=720 在 IEEE754
     * double 下恰为整数（历史上 README 警告的拒绝案例是把 2/3 手舍入成 0.6667 → 1280.064 非整数；
     * 全精度字面量无此问题，实证见 T17 报告）。
     */
    static final String SCALE_720P = "0.6666666666666666";

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

    /** 全片渲染（缺省 1080p：不传档位的既有调用点语义不变）。 */
    public Path render(Path ws) throws InterruptedException {
        return render(ws, RESOLUTION_1080P);
    }

    /** 全片渲染：resolution "1080p"/"720p"（库内历史 NULL 行按 1080p 处理）。 */
    public Path render(Path ws, String resolution) throws InterruptedException {
        return doRender(ws, null, resolution);
    }

    /** 帧段渲染（slow 短渲 / 短段目验）：追加 {@code --frames=<frameRange>}。 */
    public Path renderFrames(Path ws, String frameRange, String resolution) throws InterruptedException {
        return doRender(ws, frameRange, resolution);
    }

    private Path doRender(Path ws, String frameRange, String resolution) throws InterruptedException {
        String res = normalizeResolution(resolution);
        List<String> command = new ArrayList<>(List.of(
                "cmd", "/c", "npx", "remotion", "render", "Lecture169", "out/final.mp4"));
        if (RESOLUTION_720P.equals(res)) {
            command.add("--scale=" + SCALE_720P);
        }
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

    /**
     * 档位白名单快速失败：null/缺省归一 1080p（升级窗口期旧行 NULL 容错）；
     * 未知值抛 IllegalArgumentException——绝不静默按 1080p 整片渲（那会把非法值漂移成错误产物）。
     */
    private static String normalizeResolution(String resolution) {
        if (resolution == null || resolution.isBlank() || RESOLUTION_1080P.equals(resolution)) {
            return RESOLUTION_1080P;
        }
        if (RESOLUTION_720P.equals(resolution)) {
            return RESOLUTION_720P;
        }
        throw new IllegalArgumentException("未知 resolution: " + resolution + "（仅支持 1080p/720p）");
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
