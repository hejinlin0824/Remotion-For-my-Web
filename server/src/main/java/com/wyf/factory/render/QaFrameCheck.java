package com.wyf.factory.render;

import com.fasterxml.jackson.databind.ObjectMapper;
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
 * QA 审帧链（template/README.md §6 三步，脚本即封版资产，Global Constraint 偏差表 #4）：
 * ① {@code node scripts/pick_frames.mjs} 复算时间轴出帧清单（首行 {@code totalFrames = N}
 * 空格分隔整行落名——按前缀跳过，README 教训；行格式 {@code 名\t帧号}，名去空白+去路径分隔符）；
 * ② 单命令批量截图 {@code node scripts/qa_stills.mjs Lecture169 out/qa/frames.json out/qa}
 * ——清单落盘 out/qa/frames.json，脚本内部 bundle/composition/浏览器各取一次逐帧 renderStill
 * 复用（Task 13b：根除逐帧 {@code npx remotion still} 每帧 ~35s bundler 冷启动），stdout 行
 * {@code 名\t帧\tok}，失败行据此归因；③ {@code python scripts/qa_glm.py}，GLM key 以
 * ZHIPU_API_KEY 只进子进程 env（绝不进命令行/stdout/日志）。exit=0 → pass；exit!=0 →
 * 收集 out/qa/report.md 含 FAIL 的行为 fails，无 FAIL 行则 fails=[qa_glm exit=N]；
 * 帧清单为空 → 防呆 fail。
 */
@Component
public class QaFrameCheck {

    /** 审帧结论：pass=客观项全过；fails=FAIL 行/降级原因；framesChecked=实际截图帧数。 */
    public record QaResult(boolean pass, List<String> fails, int framesChecked) {
    }

    private record Frame(String name, int frame) {
    }

    /** qa_stills.mjs 清单行（record 字段序 = manifest 落盘字段序）。 */
    private record ManifestRow(String name, int frame) {
    }

    private static final String COMPOSITION_ID = "Lecture169";
    private static final String MANIFEST_REL = "out/qa/frames.json";
    private static final int STDERR_DIGEST_CHARS = 2000;

    private static final ObjectMapper MANIFEST_MAPPER = new ObjectMapper();

    /** pick_frames/stills 批量/qa_glm 的子进程超时（qa_glm 17 帧 × 退避重试，给足余量）。 */
    private static final Duration PICK_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration STILLS_TIMEOUT = Duration.ofMinutes(5);
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
        writeManifest(ws, frames);
        ProcessResult stills = runner.run(ws,
                List.of("cmd", "/c", "node", "scripts/qa_stills.mjs", COMPOSITION_ID,
                        MANIFEST_REL, "out/qa"),
                Map.of(), STILLS_TIMEOUT);
        if (stills.timedOut() || stills.exitCode() != 0) {
            List<String> failed = failedStills(stills.stdout());
            throw new RenderWorker.RenderException(
                    "审帧截图失败（qa_stills " + (stills.timedOut() ? "超时" : "exit=" + stills.exitCode())
                            + (failed.isEmpty() ? "" : "，失败行 " + failed) + "）：" + digest(stills.stderr()),
                    true);
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

    /** 清单落盘 out/qa/frames.json（qa_stills.mjs 输入；qa_glm 只认 *.png 不受影响）。 */
    private static void writeManifest(Path ws, List<Frame> frames) {
        Path manifest = ws.resolve(MANIFEST_REL);
        try {
            Files.createDirectories(manifest.getParent());
            List<ManifestRow> rows = frames.stream()
                    .map(frame -> new ManifestRow(frame.name(), frame.frame()))
                    .toList();
            Files.writeString(manifest, MANIFEST_MAPPER.writeValueAsString(rows), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("qa_stills manifest 写入失败：" + manifest, e);
        }
    }

    /** qa_stills stdout 失败行收集：tab 三列 {@code 名\t帧\t状态}，状态非 ok 即失败。 */
    private static List<String> failedStills(String stdout) {
        List<String> failed = new ArrayList<>();
        for (String line : stdout.split("\r?\n")) {
            String trimmed = line.strip();
            String[] parts = trimmed.split("\t");
            if (parts.length == 3 && !"ok".equals(parts[2])) {
                failed.add(trimmed);
            }
        }
        return failed;
    }

    /** stderr 摘要：bundle/rspack 报错可达上万字符，截 2000（对齐 RenderWorker 风格）。 */
    private static String digest(String stderr) {
        String stripped = stderr == null ? "" : stderr.strip();
        return stripped.length() <= STDERR_DIGEST_CHARS
                ? stripped
                : stripped.substring(0, STDERR_DIGEST_CHARS) + "…";
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
