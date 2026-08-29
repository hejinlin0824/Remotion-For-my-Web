package com.wyf.factory.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.tts.AudioMeta;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 【slow】真 junction + 真渲染管线验证：template 原样副本（golden content/audio_meta/line wav
 * 照抄，不改一字）→ mklink /J → {@code npx remotion render Lecture169 out/final.mp4 --frames=0-30}
 * （约 3 秒片段，预期 <2 分钟）→ 产物落 artifacts 且 >100KB。不需要 GLM key（渲染不调 GLM）。
 * 默认 mvn test 不跑（Global Constraint 9），{@code mvn -Pslow test -Dtest=RenderWorkerSlowIT}。
 */
@Tag("slow")
class RenderWorkerSlowIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String JOB_ID = "slow-9901";

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("真 junction + 真渲染 --frames=0-30：产物存在且 >100KB，cleanup 不伤模板本体")
    void realJunctionRealRender_shortRange() throws Exception {
        Path repoRoot = locateRepoRoot();
        Path template = repoRoot.resolve("template");

        AppProperties props = new AppProperties();
        props.setTemplateDir(template.toAbsolutePath().normalize().toString());
        props.setWorkspaceDir(tempDir.resolve("workspace").toString());
        props.setArtifactsDir(tempDir.resolve("artifacts").toString());

        ContentJson content = JSON.readValue(template.resolve("src/data/content.json").toFile(), ContentJson.class);
        AudioMeta meta = JSON.readValue(template.resolve("src/data/audio_meta.json").toFile(), AudioMeta.class);
        Map<Integer, byte[]> lineWavs = goldenLineWavs(template, meta);

        WorkspaceManager workspaceManager = new WorkspaceManager(new JdkProcessRunner(), props);
        RenderWorker renderWorker = new RenderWorker(new JdkProcessRunner(), props);

        Path ws = workspaceManager.create(JOB_ID, content, meta, lineWavs);
        try {
            assertThat(ws.resolve("node_modules"))
                    .as("junction 可穿读模板依赖").isDirectory();
            assertThat(ws.resolve("node_modules/@remotion")).isDirectory();

            Path mp4 = renderWorker.render(ws, "0-30");

            assertThat(mp4).isRegularFile();
            assertThat(Files.size(mp4)).isGreaterThan(100_000);
        } finally {
            // 无条件 cleanup（T9 评审 M 项）：mklink 成功后任何异常路径都必须拆 junction，
            // 否则 @TempDir 递归清理可能穿 junction 伤 template 本体
            workspaceManager.cleanup(JOB_ID);
        }
        assertThat(ws).doesNotExist(); // cleanup 只拆 link 不删树后的正常收尾
        assertThat(template.resolve("node_modules/@remotion")).isDirectory(); // 模板本体无伤
    }

    /** golden 台词 wav 照抄（键=lines 序，1 起）。 */
    private static Map<Integer, byte[]> goldenLineWavs(Path template, AudioMeta meta) throws IOException {
        Map<Integer, byte[]> wavs = new HashMap<>();
        for (int i = 0; i < meta.getLines().size(); i++) {
            int index = i + 1;
            Path wav = template.resolve("public/audio/lines")
                    .resolve(String.format("line_%02d.wav", index));
            wavs.put(index, Files.readAllBytes(wav));
        }
        return wavs;
    }

    /** 自 server/ 向上找含 template/src/data 的仓库根（TimelineCalcTest 同法）。 */
    private static Path locateRepoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("template/src/data/content.json"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("仓库根（含 template/）").isNotNull();
        return dir;
    }
}
