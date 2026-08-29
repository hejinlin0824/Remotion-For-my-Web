package com.wyf.factory.render;

import com.wyf.factory.config.AppProperties;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.tts.AudioMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
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
 * rmdir 瞬态失败（EPERM/句柄滞留）先做 {@value #DELETE_MAX_ATTEMPTS} 次有界重试（T12 F3 自愈），
 * 仍失败或拆后 node_modules 仍在 → 中止抛 IllegalStateException，绝不删树；
 * 删树途中再遇 node_modules 直接抛（正常流程到不了这里，纯保险）。</p>
 */
@Component
public class WorkspaceManager {

    /** 复制排除的目录名（任意层级按名匹配）。 */
    private static final Set<String> EXCLUDED_DIRS = Set.of("out", "node_modules", "data", ".git");

    private static final Duration LINK_COMMAND_TIMEOUT = Duration.ofSeconds(30);

    /** 删除侧瞬态失败（EPERM/句柄滞留，T12 F3：QA stills 子进程未放句柄）的最大尝试次数。 */
    static final int DELETE_MAX_ATTEMPTS = 3;
    /** 首次重试退避毫秒数（按尝试次数递增：500ms、1s）。 */
    static final long DELETE_BACKOFF_MILLIS = 500L;

    private static final Logger log = LoggerFactory.getLogger(WorkspaceManager.class);

    private final ProcessRunner runner;
    private final AppProperties props;

    public WorkspaceManager(ProcessRunner runner, AppProperties props) {
        this.runner = runner;
        this.props = props;
    }

    /**
     * 建工作区：已存在则先整删（含 junction 拆除）再重建。lineWavs 键从 1 起
     * （=scenes 数组序），写 line_%02d.wav。
     *
     * <p>jobId 为 String：Job.id 是 UUID（T2），工作区目录名 = jobId，
     * 断点续跑协议（T10）按 {@code workspace/{jobId}/...} 找产物。</p>
     */
    public Path create(String jobId, ContentJson content, AudioMeta meta, Map<Integer, byte[]> lineWavs)
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
    public Path workspacePath(String jobId) {
        return workspaceRoot().resolve(jobId);
    }

    /**
     * 整删工作区。顺序硬性：junction 存在则先 {@code cmd /c rmdir}（只拆链接不删目标），
     * 有界重试（{@value #DELETE_MAX_ATTEMPTS} 次）仍失败/拆后仍在 → 抛 IllegalStateException 中止；
     * 随后才递归删树（删树本身同样有界重试——QA stills 子进程句柄滞留的 EPERM 属瞬态，T12 F3）。
     */
    public void cleanup(String jobId) throws InterruptedException {
        Path ws = workspacePath(jobId);
        if (!Files.exists(ws)) {
            return;
        }
        Path junction = ws.resolve("node_modules");
        if (Files.exists(junction)) {
            removeJunctionWithRetry(junction);
        }
        deleteTreeWithRetry(ws);
    }

    /**
     * {@code cmd /c rmdir} 拆 junction，瞬态失败（timedOut/exit!=0/拆后仍在）按
     * {@value #DELETE_BACKOFF_MILLIS}ms×尝试次数退避重试，耗尽仍败 → IllegalStateException
     * 中止删树（sim-001 防御：绝不带着未拆净的 junction 删树）。不按 stderr 内容过滤：
     * Windows 下句柄滞留的报错形态多样（EPERM/拒绝访问/目录不是空的），一律当瞬态有界重试。
     */
    private void removeJunctionWithRetry(Path junction) throws InterruptedException {
        ProcessResult last = null;
        for (int attempt = 1; attempt <= DELETE_MAX_ATTEMPTS; attempt++) {
            ProcessResult r = runner.run(workspaceRoot(),
                    List.of("cmd", "/c", "rmdir", absolute(junction)), Map.of(), LINK_COMMAND_TIMEOUT);
            if (!r.timedOut() && r.exitCode() == 0 && !Files.exists(junction)) {
                return;
            }
            last = r;
            log.warn("junction 拆除第 {}/{} 次失败（exit={} stderr={}），稍后重试：{}",
                    attempt, DELETE_MAX_ATTEMPTS, r.exitCode(), brief(r.stderr()), junction);
            if (attempt < DELETE_MAX_ATTEMPTS) {
                Thread.sleep(DELETE_BACKOFF_MILLIS * attempt);
            }
        }
        throw new IllegalStateException("junction 拆除失败（" + DELETE_MAX_ATTEMPTS + " 次尝试，exit="
                + last.exitCode() + " stderr=" + brief(last.stderr()) + "），中止删树以防误删"
                + " template/node_modules 本体：" + junction);
    }

    /**
     * 删树有界重试：Windows 句柄滞留（如 QA stills 子进程仍持 workspace 内文件）表现为
     * 瞬态 FileSystemException（EPERM/拒绝访问）→ 退避重试；部分删除后重跑安全
     * （walkFileTree 从现存部分重新遍历）。防御性 IllegalStateException（删树途中撞见
     * junction，未拆净）不重试、立即抛。耗尽仍败按最后一次的 UncheckedIOException 抛出。
     */
    private void deleteTreeWithRetry(Path ws) throws InterruptedException {
        for (int attempt = 1; attempt <= DELETE_MAX_ATTEMPTS; attempt++) {
            try {
                deleteTree(ws);
                return;
            } catch (UncheckedIOException e) {
                if (attempt == DELETE_MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("workspace 删树第 {}/{} 次失败（{}），退避 {}ms 后重试：{}",
                        attempt, DELETE_MAX_ATTEMPTS, e.getMessage(),
                        DELETE_BACKOFF_MILLIS * attempt, ws);
                Thread.sleep(DELETE_BACKOFF_MILLIS * attempt);
            }
        }
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

    /**
     * 删树（junction 已确认拆除后才可能到这里）；途中再遇 node_modules 即抛（纯保险）。
     * 守卫必须先于删除生效：walkFileTree 的 preVisitDirectory 在进入目录前判定，
     * 命中 node_modules 立即中止——绝不能像逆序遍历那样先删掉 junction 的子孙
     * （= template/node_modules 本体内容）才轮到 junction 条目抛（T9 评审 M 项）。
     */
    static void deleteTree(Path ws) {
        try {
            Files.walkFileTree(ws, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if ("node_modules".equals(dir.getFileName().toString())) {
                        throw new IllegalStateException("删树途中出现 node_modules（junction 未拆净？），中止："
                                + dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if ("node_modules".equals(file.getFileName().toString())) {
                        throw new IllegalStateException("删树途中出现 node_modules（junction 未拆净？），中止："
                                + file);
                    }
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
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
