package com.wyf.factory.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.tts.AudioMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * WorkspaceManager 全流程（fake ProcessRunner 注入，零真调；真 junction 用例单独 @EnabledOnOs(WINDOWS)）：
 * 模板复制+排除规则 / 覆写三处 / junction 命令拼接 / 已存在工作区先 rmdir 拆 junction 再删树的顺序 /
 * rmdir 失败中止删树的防御 / line_NN 命名与旧 wav 清空 / mklink 失败抛 IllegalStateException。
 */
class WorkspaceManagerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path templateDir;
    private Path workspaceDir;
    private FakeRunner runner;
    private ContentJson content;
    private AudioMeta meta;

    @BeforeEach
    void setUp() throws IOException {
        templateDir = tempDir.resolve("template");
        workspaceDir = tempDir.resolve("workspace");
        runner = new FakeRunner();
        // 假 template 树：该复制的 / 该排除的 / 该被覆写的旧值
        write(templateDir, "package.json", "{\"name\":\"tpl\"}");
        write(templateDir, "src/engine/engine.ts", "export const FPS = 30;");
        write(templateDir, "src/data/content.json", "STALE-CONTENT");
        write(templateDir, "src/data/audio_meta.json", "STALE-META");
        write(templateDir, "public/audio/fixed/act1.wav", "FIXED-ACT1");
        write(templateDir, "public/audio/lines/line_01.wav", "STALE-WAV-1");
        write(templateDir, "public/audio/lines/line_02.wav", "STALE-WAV-2");
        write(templateDir, "public/audio/lines/line_03.wav", "STALE-WAV-3");
        write(templateDir, "out/junk.txt", "OLD-RENDER");
        write(templateDir, "node_modules/react/index.js", "module.exports={};");
        write(templateDir, "data/junk.txt", "OLD-DATA");
        write(templateDir, ".git/config", "[core]");

        content = JSON.readValue("""
                {"meta":{"aspect":"16:9","problemType":"计算题"},
                 "problem":{"lines":[]},
                 "knowledge":[],"steps":[],"pitfalls":[],"generalMethod":[],
                 "scenes":[
                   {"id":"s01","act":2,"component":"problem-card","ttsText":"第一句","props":{}},
                   {"id":"s02","act":2,"component":"knowledge-card","ttsText":"第二句","props":{"knowledgeRef":"k1"}}]}
                """, ContentJson.class);
        meta = JSON.readValue("""
                {"voice":"Cherry","model":"qwen-tts","rate":1.0,
                 "fps":30,"breathSec":0.18,"act5TailSec":2.0,
                 "fixed":{
                   "act1":{"file":"audio/fixed/act1.wav","durationSec":2.0},
                   "act5":{"file":"audio/fixed/act5.wav","durationSec":1.0}},
                 "lines":[
                   {"index":1,"sceneId":"s01","file":"audio/lines/line_01.wav","durationSec":1.234,"text":"第一句"},
                   {"index":2,"sceneId":"s02","file":"audio/lines/line_02.wav","durationSec":0.5,"text":"第二句"}],
                 "totalFrames":207}
                """, AudioMeta.class);
    }

    private WorkspaceManager manager() {
        return new WorkspaceManager(runner, props());
    }

    private AppProperties props() {
        AppProperties props = new AppProperties();
        props.setTemplateDir(templateDir.toString());
        props.setWorkspaceDir(workspaceDir.toString());
        return props;
    }

    private Map<Integer, byte[]> lineWavs() {
        Map<Integer, byte[]> wavs = new HashMap<>();
        wavs.put(1, "WAV-ONE".getBytes(StandardCharsets.UTF_8));
        wavs.put(2, "WAV-TWO".getBytes(StandardCharsets.UTF_8));
        return wavs;
    }

    @Test
    @DisplayName("复制+排除：普通文件照搬；out/node_modules/data/.git 不入副本")
    void copiesTemplate_excludesOutNodeModulesDataGit() throws Exception {
        Path ws = manager().create("7", content, meta, lineWavs());

        assertThat(ws).isEqualTo(workspaceDir.resolve("7")).exists();
        assertThat(ws.resolve("package.json")).exists();
        assertThat(ws.resolve("src/engine/engine.ts")).exists();
        assertThat(ws.resolve("public/audio/fixed/act1.wav")).hasContent("FIXED-ACT1");
        assertThat(ws.resolve("out")).doesNotExist();
        // node_modules 不从模板复制（fake mklink 才造出该位置），模板内文件不落地
        assertThat(ws.resolve("node_modules/react")).doesNotExist();
        assertThat(ws.resolve("data")).doesNotExist();
        assertThat(ws.resolve(".git")).doesNotExist();
    }

    @Test
    @DisplayName("覆写三处：content.json=toJson 原文；audio_meta.json=writeTo 等值；旧 STALE 值不残留")
    void overwritesThreePlaces() throws Exception {
        Path ws = manager().create("7", content, meta, lineWavs());

        String writtenContent = Files.readString(ws.resolve("src/data/content.json"), StandardCharsets.UTF_8);
        String writtenMeta = Files.readString(ws.resolve("src/data/audio_meta.json"), StandardCharsets.UTF_8);
        assertThat(writtenContent).isEqualTo(content.toJson()).doesNotContain("STALE-CONTENT");
        assertThat(writtenMeta).isEqualTo(JSON.writeValueAsString(meta)).doesNotContain("STALE-META");
    }

    @Test
    @DisplayName("junction 命令：cmd /c mklink /J 两端均为绝对路径（副本 node_modules → 模板 node_modules）")
    void junctionCommand_absolutePaths() throws Exception {
        manager().create("7", content, meta, lineWavs());

        assertThat(runner.calls).hasSize(1);
        FakeRunner.Call call = runner.calls.get(0);
        assertThat(call.cwd()).isEqualTo(workspaceDir);
        assertThat(call.command()).containsExactly("cmd", "/c", "mklink", "/J",
                workspaceDir.resolve("7").resolve("node_modules").toAbsolutePath().normalize().toString(),
                templateDir.resolve("node_modules").toAbsolutePath().normalize().toString());
    }

    @Test
    @DisplayName("mklink 失败 → IllegalStateException（渲染必挂，不吞）")
    void mklinkFailure_throwsIllegalState() throws Exception {
        runner.onMklink(call -> new ProcessResult(1, "", "拒绝访问。", false));

        assertThatThrownBy(() -> manager().create("7", content, meta, lineWavs()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mklink");
    }

    @Test
    @DisplayName("已存在工作区重建：先 rmdir 拆 junction（此刻旧树仍在），再删树，再重新 mklink")
    void recreate_rmdirBeforeTreeDeleteBeforeMklink() throws Exception {
        WorkspaceManager mgr = manager();
        Path ws = mgr.create("7", content, meta, lineWavs());
        assertThat(ws.resolve("node_modules")).exists(); // fake mklink 已造出 junction 位置
        runner.calls.clear();
        List<String> observedAtRmdir = new ArrayList<>();
        runner.onRmdir(call -> {
            // rmdir 执行时刻：旧树必须尚未删除（防御 sim-001——先拆 link 再动树）
            observedAtRmdir.add(Files.exists(ws.resolve("src/engine/engine.ts")) ? "tree-present" : "tree-gone");
            try {
                Files.deleteIfExists(Path.of(call.command().get(3))); // 模拟 rmdir 拆 link
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            return new ProcessResult(0, "", "", false);
        });

        mgr.create("7", content, meta, lineWavs());

        assertThat(observedAtRmdir).containsExactly("tree-present");
        assertThat(runner.calls).hasSize(2);
        assertThat(runner.calls.get(0).command()).contains("rmdir");
        assertThat(runner.calls.get(1).command()).contains("mklink");
        assertThat(ws.resolve("src/engine/engine.ts")).exists(); // 新树已重建
    }

    @Test
    @DisplayName("line_NN 命名与清空：模板旧 line_01..03 全清，按 1 起 NN 写入新 wav")
    void lineWavs_clearedThenWritten() throws Exception {
        Path ws = manager().create("7", content, meta, lineWavs());

        Path lines = ws.resolve("public/audio/lines");
        try (Stream<Path> files = Files.list(lines)) {
            assertThat(files.map(p -> p.getFileName().toString()).sorted())
                    .containsExactly("line_01.wav", "line_02.wav");
        }
        assertThat(lines.resolve("line_01.wav")).hasBinaryContent("WAV-ONE".getBytes(StandardCharsets.UTF_8));
        assertThat(lines.resolve("line_02.wav")).hasBinaryContent("WAV-TWO".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("cleanup：junction rmdir 在删树之前；树删净；工作区不存在则幂等无命令")
    void cleanup_rmdirThenDeleteTree_idempotent() throws Exception {
        WorkspaceManager mgr = manager();
        Path ws = mgr.create("7", content, meta, lineWavs());
        runner.calls.clear();

        mgr.cleanup("7");
        assertThat(ws).doesNotExist();
        assertThat(runner.calls).hasSize(1);
        assertThat(runner.calls.get(0).command()).containsExactly("cmd", "/c", "rmdir",
                ws.resolve("node_modules").toAbsolutePath().normalize().toString());

        runner.calls.clear();
        mgr.cleanup("7"); // 幂等：不存在 → 无命令不抛
        assertThat(runner.calls).isEmpty();
    }

    @Test
    @DisplayName("rmdir 失败即中止：抛 IllegalStateException 且绝不删树（sim-001 防御）")
    void cleanup_rmdirFailure_abortsTreeDelete() throws Exception {
        WorkspaceManager mgr = manager();
        Path ws = mgr.create("7", content, meta, lineWavs());
        runner.onRmdir(call -> new ProcessResult(1, "", "目录不是空的。", false));

        assertThatThrownBy(() -> mgr.cleanup("7"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("中止");
        assertThat(ws).exists(); // 树未动
        assertThat(ws.resolve("src/engine/engine.ts")).exists();
    }

    @Test
    @DisplayName("删树守卫先行：遇到 node_modules 即中止，其内容必须先于守卫存活（T9 评审 M 项）")
    void deleteTree_guardFiresBeforeDeletingJunctionContents() throws Exception {
        Path ws = workspaceDir.resolve("guard-ws");
        write(ws, "src/engine/engine.ts", "export const FPS = 30;");
        write(ws, "node_modules/react/index.js", "TEMPLATE-KEEPER");
        write(ws, "node_modules/react/package.json", "{\"name\":\"react\"}");

        assertThatThrownBy(() -> WorkspaceManager.deleteTree(ws))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("中止");
        // 守卫必须在实际删除任何 node_modules 内容之前生效（逆序遍历会先删 junction 子孙再抛——即删伤模板本体）
        assertThat(ws.resolve("node_modules/react/index.js")).exists();
        assertThat(ws.resolve("node_modules/react/package.json")).exists();
    }

    @Test
    @DisplayName("真 junction（Windows 实跑）：junction 可穿读；cleanup 只拆 link 不伤 template/node_modules 本体")
    @EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void realJunction_cleanupNeverTouchesTemplateTarget() throws Exception {
        write(templateDir, "node_modules/keeper.txt", "KEEP-ME");
        JdkProcessRunner real = new JdkProcessRunner();
        WorkspaceManager mgr = new WorkspaceManager(real, props());

        Path ws = mgr.create("8", content, meta, lineWavs());
        assertThat(ws.resolve("node_modules/keeper.txt")).hasContent("KEEP-ME"); // 穿读 junction
        // 已存在工作区再重建一次：真 rmdir 真删树真重挂
        mgr.create("8", content, meta, lineWavs());
        assertThat(ws.resolve("node_modules/keeper.txt")).hasContent("KEEP-ME");

        mgr.cleanup("8");
        assertThat(ws).doesNotExist();
        assertThat(templateDir.resolve("node_modules/keeper.txt")).hasContent("KEEP-ME"); // 本体无伤
    }

    private static void write(Path root, String rel, String contentText) throws IOException {
        Path p = root.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, contentText, StandardCharsets.UTF_8);
    }

    /**
     * 记录全部调用并按命名路由响应的 fake runner（后注册覆盖先注册）。
     * 默认路由模拟 mklink/rmdir 的真实副作用：mklink 造出 junction 位置的目录
     * （使 cleanup 走 rmdir 分支）、rmdir 只删该位置本身——保持与生产行为同形。
     */
    static final class FakeRunner implements ProcessRunner {

        record Call(Path cwd, List<String> command, Map<String, String> extraEnv, Duration timeout) {
        }

        private record Route(java.util.function.Predicate<Call> predicate,
                             java.util.function.Function<Call, ProcessResult> handler) {
        }

        final List<Call> calls = new ArrayList<>();
        private final Map<String, Route> routes = new java.util.LinkedHashMap<>();

        FakeRunner() {
            onMklink(call -> {
                try {
                    Files.createDirectories(Path.of(call.command().get(4)));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return new ProcessResult(0, "", "", false);
            });
            onRmdir(call -> {
                try {
                    Files.deleteIfExists(Path.of(call.command().get(3)));
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                return new ProcessResult(0, "", "", false);
            });
        }

        void onMklink(java.util.function.Function<Call, ProcessResult> handler) {
            routes.put("mklink", new Route(call -> call.command().contains("mklink"), handler));
        }

        void onRmdir(java.util.function.Function<Call, ProcessResult> handler) {
            routes.put("rmdir", new Route(call -> call.command().contains("rmdir"), handler));
        }

        @Override
        public ProcessResult run(Path cwd, List<String> command, Map<String, String> extraEnv, Duration timeout) {
            Call call = new Call(cwd, List.copyOf(command), Map.copyOf(extraEnv), timeout);
            calls.add(call);
            return routes.values().stream()
                    .filter(route -> route.predicate().test(call))
                    .findFirst()
                    .map(route -> route.handler().apply(call))
                    .orElse(new ProcessResult(0, "", "", false));
        }
    }
}
