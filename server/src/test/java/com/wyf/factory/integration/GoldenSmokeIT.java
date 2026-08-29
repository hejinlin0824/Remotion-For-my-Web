package com.wyf.factory.integration;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.FileVisitResult;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 【slow】golden 全链路冒烟（plan Task 11）——第一条真实全链路实证：
 * 起真实 Spring 服务（RANDOM_PORT、真 H2 文件库、真编排器线程），
 * 文本题 POST /api/v1/jobs → 每 10s 轮询 GET /jobs/{id}（上限 50 分钟）→ 断言终态 DONE；
 * DONE 后断言 GET /jobs/{id}/video 200 且 >1MB、stageHistory 覆盖全部 7 个阶段、
 * artifacts 目录 final.mp4 落盘。
 *
 * <p>真调外部资源：GLM 三工位 + V4 judge（coding 端点）、DashScope TTS、
 * npx remotion 全片渲染（1080p，≈10 分钟）、GLM 审帧 QA。预计墙钟 10-45 分钟。</p>
 *
 * <p>前置（不满足 {@code assumeTrue} 跳过，CI 不依赖本 IT）：
 * ①GLM key env（ZHIPU_API_KEY / ZHIPUAI_API_KEY / GLM_API_KEY）；
 * ②DASHSCOPE_API_KEY；③GLM coding 端点覆盖 env——GLM_BASE_URL（T5/T6 slow 先例，
 * 本测试经 @DynamicPropertySource 映射到 app.glm.base-url）或 APP_GLM_BASE_URL
 * （Spring relaxed binding），默认 v4 端点对本机 key 稳定 429。</p>
 *
 * <p>隔离：H2/workspace/artifacts/服务日志全部收进 {@code server/target/test-data/}
 * （跑前清空；清空对 workspace 下的 node_modules junction 先 {@code cmd /c rmdir}
 * 拆链再删树，绝不穿 junction 伤 template/node_modules 本体，sim-001 教训）。
 * 失败现场（test-data 全目录）保留不删，供诊断。</p>
 *
 * <p>断言口径说明（与 brief 的差异）：生产 stageHistory 只写 ENTER（
 * {@link com.wyf.factory.domain.StageHistoryEntry}.STATE_EXIT 全库无写入点），
 * 故"全部 7 个阶段的历史记录"按 ENTER 断言；QA report.md 存活在 workspace
 * out/qa/（DONE 收尾即整删 workspace，存活秒级窗口），测试以 1s 粒度看护线程
 * 尽力快照，另以 DONE 历史条目 note 内"QA 通过（N 帧）"作为审帧通过的最强证据。</p>
 *
 * <p>状态（2026-08-29 首跑）：本 IT 在 Spring 上下文启动即暴露生产装配 bug——
 * Secrets / GlmClient / DashScopeTts / TtsPipeline 四个 bean 各有「生产构造器 +
 * 测试构造器」两个 public 构造器且无 @Autowired，Spring 无法择一，
 * {@code Failed to instantiate [...]: No default constructor found}（HEAD=c724d32 全上下文
 * 从未启动过，切片测试掩盖至今）。生产修复（生产构造器标 @Autowired 或测试构造器收窄可见性）
 * 后本 IT 应可直接跑通。诊断详见
 * .superpowers/sdd/2026-08-29-phase2-java-service/task-11-report.md。</p>
 */
@Tag("slow")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("golden 全链路冒烟：文本题 → 真 GLM + 校验链 + 真 TTS + 真渲染 + 真 QA → DONE")
class GoldenSmokeIT {

    /** brief 硬编码测试题（golden 模板内置示例题同族，T5 slow IT fixture 同一原文）。 */
    private static final String QUESTION = "已知函数 f(x)=x³+ax²+x，若 f(x) 在 R 上单调递增，求实数 a 的取值范围。";

    /** 测试专用文件区：H2 文件库 / workspace / artifacts / server.log / timeline.txt。 */
    private static final Path TEST_DATA = Path.of("target", "test-data");
    private static final Path TIMELINE = TEST_DATA.resolve("timeline.txt");

    private static final long POLL_INTERVAL_MILLIS = 10_000L;
    private static final long POLL_BUDGET_MILLIS = 50 * 60_000L;
    private static final long MIN_VIDEO_BYTES = 1024L * 1024L;

    /** 正向链 7 个阶段（QUEUED 入队历史之外，brief 要求全覆盖的阶段序列）。 */
    private static final List<String> PIPELINE_STAGES = List.of(
            "EXTRACTING", "GENERATING", "REVIEWING", "SPEAKING", "RENDERING", "QA", "DONE");
    private static final Set<String> TERMINAL = Set.of("DONE", "FAILED", "CANCELLED");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    /** 每个状态首次观测时间戳（轮询粒度 10s）。 */
    private final Map<String, Long> firstSeen = new LinkedHashMap<>();
    private final AtomicBoolean terminalSeen = new AtomicBoolean(false);
    private volatile Path qaReportSnapshot;

    /** 测试专用配置：真 H2 文件库与 workspace/artifacts 全部收进 target/test-data。 */
    @DynamicPropertySource
    static void testProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:h2:file:./target/test-data/jobs;AUTO_SERVER=TRUE");
        registry.add("app.workspace-dir", () -> "./target/test-data/workspace");
        registry.add("app.artifacts-dir", () -> "./target/test-data/artifacts");
        // 服务日志落文件：后台跑时 tail 盯编排器各阶段迁移进度
        registry.add("logging.file.name", () -> "./target/test-data/server.log");
        // coding 端点覆盖（T5/T6 slow 先例）：GLM_BASE_URL 优先，兼容 APP_GLM_BASE_URL
        String endpoint = firstNonBlank(System.getenv("GLM_BASE_URL"), System.getenv("APP_GLM_BASE_URL"));
        if (endpoint != null) {
            registry.add("app.glm.base-url", () -> endpoint.strip());
        }
    }

    @BeforeAll
    static void prepare() {
        System.out.println("[GoldenSmoke] env 探测（只报在否不报值）: ZHIPU_API_KEY=" + envSet("ZHIPU_API_KEY")
                + " ZHIPUAI_API_KEY=" + envSet("ZHIPUAI_API_KEY")
                + " GLM_API_KEY=" + envSet("GLM_API_KEY")
                + " DASHSCOPE_API_KEY=" + envSet("DASHSCOPE_API_KEY")
                + " GLM_BASE_URL=" + envSet("GLM_BASE_URL")
                + " APP_GLM_BASE_URL=" + envSet("APP_GLM_BASE_URL"));
        cleanTestData();
        boolean glmKey = envSet("ZHIPU_API_KEY") || envSet("ZHIPUAI_API_KEY") || envSet("GLM_API_KEY");
        assumeTrue(glmKey, "无 GLM key（ZHIPU_API_KEY / ZHIPUAI_API_KEY / GLM_API_KEY），跳过 golden 冒烟");
        assumeTrue(envSet("DASHSCOPE_API_KEY"), "无 DASHSCOPE_API_KEY（TTS 必需），跳过 golden 冒烟");
        assumeTrue(envSet("GLM_BASE_URL") || envSet("APP_GLM_BASE_URL"),
                "GLM 端点未覆盖为 coding 端点（GLM_BASE_URL / APP_GLM_BASE_URL 均未设；"
                        + "默认 v4 对本机 key 稳定 429），跳过 golden 冒烟");
        System.out.println("[GoldenSmoke] 前置就绪。预计墙钟 10-45 分钟"
                + "（1080p 全片渲染 ≈10 分钟最慢，QA 审帧 17 帧次之）。");
    }

    @Test
    @DisplayName("文本题全链路：POST → 轮询 ≤50min 至 DONE → 成片 >1MB + 7 阶段历史 + artifacts")
    void goldenSmoke_textJob_fullChain_reachesDone() throws Exception {
        long submittedAt = System.currentTimeMillis();
        note("==== golden 冒烟开始 ====");
        note("题目: " + QUESTION);
        note("服务端口: " + port);

        // 1. 入队
        ResponseEntity<Map> created = rest.postForEntity("/api/v1/jobs",
                Map.of("inputType", "TEXT", "text", QUESTION, "aspect", "16:9", "voice", "Cherry"),
                Map.class);
        assertThat(created.getStatusCode()).as("POST /api/v1/jobs → 202").isEqualTo(HttpStatus.ACCEPTED);
        Object jobIdRaw = created.getBody() == null ? null : created.getBody().get("jobId");
        assertThat(jobIdRaw).as("响应含 jobId").isNotNull();
        String jobId = String.valueOf(jobIdRaw);
        note("POST → 202，jobId=" + jobId);

        // 2. 轮询至终态（每 10s，上限 50 分钟）；QA 阶段同步看护快照 qa report
        startQaReportWatcher(jobId);
        JobSnapshot last = null;
        String terminal = null;
        long deadline = System.currentTimeMillis() + POLL_BUDGET_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<JobSnapshot> poll =
                    rest.getForEntity("/api/v1/jobs/" + jobId, JobSnapshot.class);
            assertThat(poll.getStatusCode()).as("GET /api/v1/jobs/{id}").isEqualTo(HttpStatus.OK);
            last = poll.getBody();
            terminal = recordStatus(last);
            if (terminal != null) {
                break;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        long finishedAt = System.currentTimeMillis();
        printStageTable(submittedAt, finishedAt);

        // 3. 终态断言（失败/超时附带完整诊断，现场保留在 target/test-data）
        if (terminal == null) {
            failWithDiagnostics("轮询 " + (POLL_BUDGET_MILLIS / 60_000) + " 分钟未达终态（最后状态 "
                    + (last == null ? "?" : last.status()) + "）", last);
        }
        assertThat(terminal).as("终态").isEqualTo("DONE");
        assertThat(last.status()).isEqualTo("DONE");

        // 4. stageHistory 覆盖全部 7 个阶段（ENTER，见类注释断言口径）
        List<Hist> history = last.stageHistory() == null ? List.of() : last.stageHistory();
        List<String> enteredStages = history.stream()
                .filter(h -> "ENTER".equals(h.state()))
                .map(Hist::stage)
                .distinct()
                .toList();
        assertThat(enteredStages)
                .as("stageHistory 覆盖全部 7 个阶段（生产只写 ENTER，EXIT 无写入点——报告披露）")
                .containsExactlyInAnyOrderElementsOf(PIPELINE_STAGES);
        assertThat(enteredStages)
                .as("阶段按正向链推进（QA→RENDERING 重渲回退允许重复，允许插入额外阶段）")
                .containsSubsequence(PIPELINE_STAGES);
        String doneNote = history.stream()
                .filter(h -> "DONE".equals(h.stage()))
                .map(Hist::note)
                .findFirst().orElse("");
        assertThat(doneNote).as("DONE 历史条目带 QA 审帧通过证据").contains("QA 通过（");

        // 5. 成片流：GET /video 200 且 >1MB
        ResponseEntity<byte[]> video = rest.getForEntity("/api/v1/jobs/" + jobId + "/video", byte[].class);
        assertThat(video.getStatusCode()).as("GET /jobs/{id}/video").isEqualTo(HttpStatus.OK);
        assertThat(String.valueOf(video.getHeaders().getContentType()))
                .as("video Content-Type").contains("video/mp4");
        byte[] body = video.getBody();
        assertThat(body).as("video body").isNotNull();
        assertThat((long) body.length).as("成片 >1MB").isGreaterThan(MIN_VIDEO_BYTES);
        note(String.format("GET /video → 200，%,d 字节（%.1f MB）", body.length, body.length / 1048576.0));

        // 6. artifacts 目录：final.mp4 落盘（qa report 见类注释——尽力快照）
        assertThat(last.artifactsDir()).as("artifactsDir 落库").isNotBlank();
        Path finalMp4 = Path.of(last.artifactsDir()).resolve("final.mp4");
        assertThat(finalMp4).as("artifacts/{jobId}/final.mp4").isRegularFile();
        long mp4Size = Files.size(finalMp4);
        assertThat(mp4Size).as("artifacts final.mp4 >1MB").isGreaterThan(MIN_VIDEO_BYTES);
        note(String.format("artifacts final.mp4 = %,d 字节（%.1f MB）: %s",
                mp4Size, mp4Size / 1048576.0, finalMp4.toAbsolutePath()));
        if (qaReportSnapshot != null) {
            note("QA report 快照: " + qaReportSnapshot.toAbsolutePath());
        } else {
            note("提示：qa report.md 未捕获到快照（只存在于 workspace out/qa/，DONE 收尾即删 workspace）"
                    + "——QA 通过证据以 DONE 历史条目 note 为准");
        }

        note("==== golden 冒烟通过 ====");
    }

    // ------------------------------------------------------------------ 轮询与记录

    /** 状态变化记录进时间线；返回终态名（未终态返回 null）。 */
    private String recordStatus(JobSnapshot snap) {
        String status = snap.status() == null ? "?" : snap.status();
        if (!firstSeen.containsKey(status)) {
            firstSeen.put(status, System.currentTimeMillis());
            note(String.format("[%s] status → %s", hhmmss(System.currentTimeMillis()), status));
            if (snap.stageHistory() != null && !snap.stageHistory().isEmpty()) {
                Hist newest = snap.stageHistory().get(snap.stageHistory().size() - 1);
                note(String.format("    最新历史: %s %s %s", newest.stage(), newest.state(),
                        newest.note() == null ? "" : newest.note()));
            }
            if (snap.lastError() != null && !snap.lastError().isBlank()) {
                note("    lastError(尾400): " + tail(snap.lastError(), 400));
            }
        }
        if (TERMINAL.contains(status)) {
            terminalSeen.set(true);
            return status;
        }
        return null;
    }

    /** 阶段耗时表：各状态首次观测时间戳差值（轮询粒度 10s，供 E2E 报告引用）。 */
    private void printStageTable(long submittedAt, long finishedAt) {
        note("");
        note("==== 各阶段耗时表（10s 轮询粒度） ====");
        long cursor = submittedAt;
        for (Map.Entry<String, Long> e : firstSeen.entrySet()) {
            note(String.format("  %-11s %9.1fs   （%s 首次观测）",
                    e.getKey(), (e.getValue() - cursor) / 1000.0, hhmmss(e.getValue())));
            cursor = e.getValue();
        }
        note(String.format("  %-11s %9.1fs（POST → 终态观测，含轮询粒度误差）",
                "TOTAL", (finishedAt - submittedAt) / 1000.0));
        note("");
    }

    /** QA report 看护线程：QA 阶段 report.md 出现即快照（DONE 收尾删 workspace，存活窗口秒级）。 */
    private void startQaReportWatcher(String jobId) {
        Path wsReport = TEST_DATA.resolve("workspace").resolve(jobId)
                .resolve("out").resolve("qa").resolve("report.md");
        Thread watcher = new Thread(() -> {
            long deadline = System.currentTimeMillis() + POLL_BUDGET_MILLIS;
            while (System.currentTimeMillis() < deadline && !terminalSeen.get()) {
                if (Files.isRegularFile(wsReport)) {
                    try {
                        Path snap = TEST_DATA.resolve("qa-report.md");
                        Files.copy(wsReport, snap, StandardCopyOption.REPLACE_EXISTING);
                        qaReportSnapshot = snap;
                        note("已快照 QA report.md → " + snap.toAbsolutePath());
                    } catch (IOException e) {
                        note("QA report 快照失败：" + e);
                    }
                    return;
                }
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "qa-report-snapshot");
        watcher.setDaemon(true);
        watcher.start();
    }

    /** 失败诊断：完整状态 + 阶段历史落时间线，并抛带上下文的断言错误。 */
    private void failWithDiagnostics(String headline, JobSnapshot last) {
        StringBuilder sb = new StringBuilder("==== 失败诊断 ====\n").append(headline).append('\n');
        if (last != null) {
            sb.append("status=").append(last.status()).append("  stage=").append(last.stage()).append('\n');
            if (last.errorMessage() != null) {
                sb.append("errorMessage=").append(last.errorMessage()).append('\n');
            }
            if (last.lastError() != null && !last.lastError().isBlank()) {
                sb.append("lastError(尾800)=\n").append(tail(last.lastError(), 800)).append('\n');
            }
            if (last.stageHistory() != null) {
                sb.append("stageHistory:\n");
                for (Hist h : last.stageHistory()) {
                    sb.append("  ").append(h.at()).append("  ").append(h.stage()).append(' ')
                            .append(h.state()).append("  ").append(h.note()).append('\n');
                }
            }
        }
        note(sb.toString());
        throw new AssertionError(headline + "\n（完整诊断见 " + TIMELINE.toAbsolutePath() + "）\n" + sb);
    }

    // ------------------------------------------------------------------ 测试数据区清理（junction 安全）

    /**
     * 跑前清空 target/test-data。workspace 下的 node_modules junction 必须先
     * {@code cmd /c rmdir} 拆链（只拆链接不碰目标），否则递归删除会穿 junction
     * 删掉 template/node_modules 本体（sim-001 教训）；删树途中再遇 node_modules 即中止。
     */
    private static void cleanTestData() {
        if (!Files.exists(TEST_DATA)) {
            return;
        }
        Path workspace = TEST_DATA.resolve("workspace");
        if (Files.isDirectory(workspace)) {
            try (var dirs = Files.list(workspace)) {
                for (Path job : dirs.toList()) {
                    Path junction = job.resolve("node_modules");
                    if (Files.exists(junction)) {
                        unlinkJunction(junction);
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("扫描 test-data/workspace 失败", e);
            }
        }
        deleteTree(TEST_DATA);
    }

    private static void unlinkJunction(Path junction) {
        try {
            Process p = new ProcessBuilder("cmd", "/c", "rmdir",
                    junction.toAbsolutePath().normalize().toString()).start();
            boolean exited = p.waitFor(30, TimeUnit.SECONDS);
            if (!exited || p.exitValue() != 0 || Files.exists(junction)) {
                throw new IllegalStateException("junction 拆除失败（exit="
                        + (exited ? p.exitValue() : "timeout") + "），中止清理以防误删模板依赖：" + junction);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("junction 拆除被中断", e);
        } catch (IOException e) {
            throw new UncheckedIOException("junction 拆除进程失败", e);
        }
    }

    private static void deleteTree(Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if ("node_modules".equals(dir.getFileName().toString())) {
                        throw new IllegalStateException("清删除途中出现 node_modules（junction 未拆净？），中止：" + dir);
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
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
            throw new UncheckedIOException("测试数据清理失败：" + root, e);
        }
    }

    // ------------------------------------------------------------------ 小工具与视图

    private static void note(String line) {
        System.out.println(line);
        try {
            Files.createDirectories(TIMELINE.getParent());
            Files.writeString(TIMELINE, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // 时间线文件写失败不影响测试本体（stdout 已有）
        }
    }

    private static String hhmmss(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
    }

    private static String tail(String s, int max) {
        String stripped = s == null ? "" : s.strip();
        return stripped.length() <= max ? stripped : "…" + stripped.substring(stripped.length() - max);
    }

    private static boolean envSet(String name) {
        String value = System.getenv(name);
        return value != null && !value.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** 轮询视图（只取断言所需字段；日期以原始字符串承载，不依赖上下文 ObjectMapper）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record JobSnapshot(String jobId, String status, String stage, String errorMessage, String lastError,
                       String artifactsDir, List<Hist> stageHistory) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Hist(String stage, String state, String note, String at) {
    }
}
