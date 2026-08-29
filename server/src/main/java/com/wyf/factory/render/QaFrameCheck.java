package com.wyf.factory.render;

import com.wyf.factory.config.Secrets;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * QA 审帧链（template/README.md §6 三步照搬，脚本即封版资产，Global Constraint 偏差表 #4）：
 * ① {@code node scripts/pick_frames.mjs} 复算时间轴出帧清单（首行 {@code totalFrames = N}
 * 空格分隔整行落名——按前缀跳过，README 教训；行格式 {@code 名\t帧号}）；② 逐帧
 * {@code npx remotion still Lecture169 out/qa/<名>.png --frame=<N>}（名去空白+去路径分隔符）；
 * ③ {@code python scripts/qa_glm.py}，GLM key 以 ZHIPU_API_KEY 只进子进程 env
 * （绝不进命令行/stdout/日志）。exit=0 → pass；exit!=0 → 收集 out/qa/report.md 含 FAIL 的行
 * 为 fails，无 FAIL 行则 fails=[qa_glm exit=N]；帧清单为空 → 防呆 fail。
 */
@Component
public class QaFrameCheck {

    /** 审帧结论：pass=客观项全过；fails=FAIL 行/降级原因；framesChecked=实际截图帧数。 */
    public record QaResult(boolean pass, List<String> fails, int framesChecked) {
    }

    private record Frame(String name, int frame) {
    }

    /** pick_frames/still/qa_glm 的子进程超时（qa_glm 17 帧 × 退避重试，给足余量）。 */
    private static final Duration PICK_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration STILL_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration QA_TIMEOUT = Duration.ofMinutes(30);

    private final ProcessRunner runner;
    private final Secrets secrets;

    public QaFrameCheck(ProcessRunner runner, Secrets secrets) {
        this.runner = runner;
        this.secrets = secrets;
    }

    public QaResult check(Path ws) throws InterruptedException {
        ProcessResult pick = runner.run(ws,
                List.of("cmd", "/c", "node", "scripts/pick_frames.mjs"), Map.of(), PICK_TIMEOUT);
        if (pick.timedOut() || pick.exitCode() != 0) {
            throw new RenderWorker.RenderException("pick_frames 失败 exit=" + pick.exitCode()
                    + "：" + pick.stderr(), true);
        }
        List<Frame> frames = parseFrames(pick.stdout());
        if (frames.isEmpty()) {
            return new QaResult(false, List.of("pick_frames 无帧行"), 0);
        }
        for (Frame frame : frames) {
            ProcessResult still = runner.run(ws,
                    List.of("cmd", "/c", "npx", "remotion", "still", "Lecture169",
                            "out/qa/" + frame.name() + ".png", "--frame=" + frame.frame()),
                    Map.of(), STILL_TIMEOUT);
            if (still.timedOut() || still.exitCode() != 0) {
                throw new RenderWorker.RenderException("审帧截图失败（" + frame.name() + " 帧 "
                        + frame.frame() + "）exit=" + still.exitCode() + "：" + still.stderr(), true);
            }
        }
        ProcessResult qa = runner.run(ws,
                List.of("cmd", "/c", "python", "scripts/qa_glm.py"),
                Map.of("ZHIPU_API_KEY", secrets.glmKey()), QA_TIMEOUT);
        if (!qa.timedOut() && qa.exitCode() == 0) {
            return new QaResult(true, List.of(), frames.size());
        }
        return new QaResult(false, collectFails(ws, qa), frames.size());
    }

    /**
     * 帧行解析：跳过空行与 totalFrames 开头的行（README §6 教训：首行空格分隔，
     * 整行落入名段）；tab 分隔，行名去全部空白与路径分隔符（README tr -d '/'）。
     */
    private static List<Frame> parseFrames(String stdout) {
        List<Frame> frames = new ArrayList<>();
        for (String line : stdout.split("\r?\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("totalFrames")) {
                continue;
            }
            String[] parts = trimmed.split("\t");
            if (parts.length < 2) {
                continue;
            }
            try {
                String name = parts[0].replaceAll("\\s+", "").replace("/", "").replace("\\", "");
                frames.add(new Frame(name, Integer.parseInt(parts[1].strip())));
            } catch (NumberFormatException ignored) {
                // 非帧行（告警等杂讯），跳过
            }
        }
        return frames;
    }

    /** exit!=0 的 fails 归因：report.md 含 FAIL 的行优先，否则降级为 qa_glm exit=N。 */
    private List<String> collectFails(Path ws, ProcessResult qa) {
        List<String> fails = new ArrayList<>();
        Path report = ws.resolve("out/qa/report.md");
        if (Files.isRegularFile(report)) {
            try {
                for (String line : Files.readAllLines(report, StandardCharsets.UTF_8)) {
                    String trimmed = line.strip();
                    if (trimmed.contains("FAIL")) {
                        fails.add(trimmed);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("qa 报告读取失败：" + report, e);
            }
        }
        if (fails.isEmpty()) {
            fails.add(qa.timedOut() ? "qa_glm 超时" : "qa_glm exit=" + qa.exitCode());
        }
        return fails;
    }
}
