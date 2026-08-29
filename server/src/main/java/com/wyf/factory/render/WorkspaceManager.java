package com.wyf.factory.render;

import com.wyf.factory.config.AppProperties;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.tts.AudioMeta;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 任务工作区（封版模板副本，Global Constraint 1）：workspace/{jobId}/ 下复制
 * app.templateDir（排除 out/、node_modules/、data/、.git——按目录名任意层匹配），
 * mklink /J 挂模板 node_modules（绝对路径，失败抛 IllegalStateException——渲染必挂），
 * 再覆写副本仅有的三处：src/data/content.json、src/data/audio_meta.json、
 * public/audio/lines/line_NN.wav（NN 两位 01 起，键=scenes 数组序，写前清空副本 lines 目录）。
 *
 * <p>拆除防御（sim-001 教训：删树穿越 junction 会删掉 template/node_modules 本体）：
 * cleanup 恒先 {@code cmd /c rmdir} 拆 junction（rmdir 对 junction 恰好只拆链接不碰目标），
 * rmdir 失败或拆后 node_modules 仍在 → 中止抛 IllegalStateException，绝不删树；
 * 删树途中再遇 node_modules 直接抛（正常流程到不了这里，纯保险）。</p>
 */
@Component
public class WorkspaceManager {

    /** 复制排除的目录名（任意层级按名匹配）。 */
    private static final Set<String> EXCLUDED_DIRS = Set.of("out", "node_modules", "data", ".git");

    private static final Duration LINK_COMMAND_TIMEOUT = Duration.ofSeconds(30);

    private final ProcessRunner runner;
    private final AppProperties props;

    public WorkspaceManager(ProcessRunner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
    }

    /**
     * 建工作区：已存在则先整删（含 junction 拆除）再重建。lineWavs 键从 1 起
     * （=scenes 数组序），写 line_%02d.wav。
     */
    public Path create(long jobId, ContentJson content, AudioMeta meta, Map<Integer, byte[]> lineWavs)
            throws InterruptedException {
        Path ws = workspacePath(jobId);
        if (Files.exists(ws)) {
            cleanup(jobId);
        }
        Path root = workspaceRoot();
        try {
            Files.createDirectories(root);
            Files.createDirectories(ws);
            copyTemplate(ws);
            createJunction(ws);
            overwriteContent(ws, content, meta, lineWavs == null ? Map.of() : lineWavs);
        } catch (IOException e) {
            throw new UncheckedIOException("工作区创建失败：" + ws, e);
        }
        return ws;
    }

    /** 任务工作区目录：{workspaceDir}/{jobId}。 */
    public Path workspacePath(long jobId) {
        return workspaceRoot().resolve(String.valueOf(jobId));
    }

    /**
     * 整删工作区。顺序硬性：junction 存在则先 {@code cmd /c rmdir}（只拆链接不删目标），
     * 失败/拆后仍在 → 抛 IllegalStateException 中止；随后才递归删树。
     */
    public void cleanup(long jobId) throws InterruptedException {
        Path ws = workspacePath(jobId);
        if (!Files.exists(ws)) {
            return;
        }
        Path junction = ws.resolve("node_modules");
        if (Files.exists(junction)) {
            ProcessResult r = runner.run(workspaceRoot(),
                    List.of("cmd", "/c", "rmdir", absolute(junction)), Map.of(), LINK_COMMAND_TIMEOUT);
            if (r.timedOut() || r.exitCode() != 0 || Files.exists(junction)) {
                throw new IllegalStateException("junction 拆除失败（exit=" + r.exitCode()
                        + " stderr=" + brief(r.stderr()) + "），中止删树以防误删 template/node_modules 本体："
                        + junction);
            }
        }
        deleteTree(ws);
    }

    private Path workspaceRoot() {
        return Path.of(props.getWorkspaceDir());
    }

    /** 递归复制模板（跳过排除目录名）；副本先不带 golden 内容，三处覆写随后。 */
    private void copyTemplate(Path ws) throws IOException {
        Path template = Path.of(props.getTemplateDir());
        try (Stream<Path> paths = Files.walk(template)) {
            for (Path source : paths.toList()) {
                String rel = template.relativize(source).toString();
                if (rel.isEmpty()) {
                    continue;
                }
                if (excluded(template.relativize(source))) {
                    continue;
                }
                Path target = ws.resolve(rel);
                if (Files.isDirectory(source)) {
                    Files.createDirectories(target);
                } else {
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** 相对路径上任一目录段命中排除名即跳过。 */
    private static boolean excluded(Path relative) {
        for (Path segment : relative) {
            if (EXCLUDED_DIRS.contains(segment.toString())) {
                return true;
            }
        }
        return false;
    }

    /** cmd /c mklink /J 绝对路径挂 junction；失败抛 IllegalStateException（渲染必挂，不吞）。 */
    private void createJunction(Path ws) throws InterruptedException {
        Path link = ws.resolve("node_modules");
        Path target = Path.of(props.getTemplateDir()).resolve("node_modules").toAbsolutePath().normalize();
        ProcessResult r = runner.run(workspaceRoot(),
                List.of("cmd", "/c", "mklink", "/J", absolute(link), absolute(target)),
                Map.of(), LINK_COMMAND_TIMEOUT);
        if (r.timedOut() || r.exitCode() != 0) {
            throw new IllegalStateException("node_modules junction 创建失败（mklink exit=" + r.exitCode()
                    + " stderr=" + brief(r.stderr()) + "），渲染必挂：" + link + " → " + target);
        }
    }

    /** 覆写副本仅有的三处（Global Constraint 1）；lines 目录先清空再写。 */
    private void overwriteContent(Path ws, ContentJson content, AudioMeta meta, Map<Integer, byte[]> lineWavs)
            throws IOException {
        Path contentJson = ws.resolve("src/data/content.json");
        Path audioMetaJson = ws.resolve("src/data/audio_meta.json");
        Files.createDirectories(contentJson.getParent());
        Files.createDirectories(audioMetaJson.getParent());
        Files.writeString(contentJson, content.toJson(), StandardCharsets.UTF_8);
        meta.writeTo(audioMetaJson);

        Path linesDir = ws.resolve("public/audio/lines");
        Files.createDirectories(linesDir);
        try (Stream<Path> stale = Files.list(linesDir)) {
            for (Path file : stale.toList()) {
                Files.delete(file);
            }
        }
        for (Map.Entry<Integer, byte[]> entry : lineWavs.entrySet()) {
            Path wav = linesDir.resolve(String.format("line_%02d.wav", entry.getKey()));
            Files.createDirectories(wav.getParent());
            Files.write(wav, entry.getValue());
        }
    }

    /** 删树（junction 已确认拆除后才可能到这里）；途中再遇 node_modules 即抛（纯保险）。 */
    private static void deleteTree(Path ws) throws InterruptedException {
        try (Stream<Path> paths = Files.walk(ws)) {
            // 逆序 = 子先于父（Path 比较父为前缀必小于子）
            for (Path p : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                if ("node_modules".equals(p.getFileName().toString())) {
                    throw new IllegalStateException("删树途中出现 node_modules（junction 未拆净？），中止："
                            + p);
                }
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("工作区删树失败：" + ws, e);
        }
    }

    private static String absolute(Path p) {
        return p.toAbsolutePath().normalize().toString();
    }

    private static String brief(String s) {
        String stripped = s == null ? "" : s.strip();
        return stripped.length() <= 200 ? stripped : stripped.substring(0, 200) + "…";
    }
}
