package com.wyf.factory.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.tts.AudioMeta;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 时间轴公式对拍：pick_frames.mjs:8-25 逐行镜像。
 * 黄金断言 = golden audio_meta.json（17 场）→ totalFrames==5334（pick_frames.mjs 实测输出）。
 */
class TimelineCalcTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static Path goldenMetaFile;

    @BeforeAll
    static void locateGolden() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("template/src/data/audio_meta.json"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("仓库根（含 template/src/data/audio_meta.json）").isNotNull();
        goldenMetaFile = dir.resolve("template/src/data/audio_meta.json");
    }

    @Test
    @DisplayName("黄金断言：golden 17 场 → totalFrames == 5334")
    void golden17Scenes_totalFrames5334() throws IOException {
        AudioMeta golden = JSON.readValue(goldenMetaFile.toFile(), AudioMeta.class);

        assertThat(golden.getLines()).hasSize(17);
        assertThat(TimelineCalc.totalFrames(golden)).isEqualTo(5334);
    }

    @Test
    @DisplayName("手造 2 场小例手算对拍：act1=60 起，逐场 dur+breath，act5 起点抹气口，尾停 90 帧 → 207")
    void handMadeTwoScenes_handComputed() throws IOException {
        String metaJson = """
                {
                  "voice": "Cherry", "model": "qwen-tts", "rate": 1.0,
                  "fps": 30, "breathSec": 0.18, "act5TailSec": 2.0,
                  "fixed": {
                    "act1": {"file": "audio/fixed/act1.wav", "durationSec": 2.0},
                    "act5": {"file": "audio/fixed/act5.wav", "durationSec": 1.0}
                  },
                  "lines": [
                    {"index": 1, "sceneId": "s01", "file": "audio/lines/line_01.wav", "durationSec": 1.234, "text": "第一句"},
                    {"index": 2, "sceneId": "s02", "file": "audio/lines/line_02.wav", "durationSec": 0.5, "text": "第二句"}
                  ]
                }
                """;
        AudioMeta meta = JSON.readValue(metaJson, AudioMeta.class);

        // 手算：t=round(2.0*30)=60；场1 dur=round(1.234*30)=37 → t=60+37+5=102；
        // 场2 dur=round(0.5*30)=15 → t=102+15+5=122；act5Start=122-5=117；
        // total=117+round((1.0+2.0)*30)=117+90=207
        assertThat(TimelineCalc.totalFrames(meta)).isEqualTo(207);
    }

    @Test
    @DisplayName("AudioMeta 反序列化 golden：fixed 两句时长与键名逐字对齐（5.201/5.901）")
    void goldenDeserialization_fixedEntries() throws IOException {
        AudioMeta golden = JSON.readValue(goldenMetaFile.toFile(), AudioMeta.class);

        assertThat(golden.getVoice()).isEqualTo("Cherry");
        assertThat(golden.getModel()).isEqualTo("qwen-tts");
        assertThat(golden.getRate()).isEqualTo(1.0);
        assertThat(golden.getFps()).isEqualTo(30);
        assertThat(golden.getBreathSec()).isEqualTo(0.18);
        assertThat(golden.getAct5TailSec()).isEqualTo(2.0);
        assertThat(golden.getFixed().get("act1").getDurationSec()).isEqualTo(5.201);
        assertThat(golden.getFixed().get("act1").getFile()).isEqualTo("audio/fixed/act1.wav");
        assertThat(golden.getFixed().get("act5").getDurationSec()).isEqualTo(5.901);
        assertThat(golden.getLines().get(0).getSceneId()).isEqualTo("s01");
        assertThat(golden.getLines().get(0).getIndex()).isEqualTo(1);
        assertThat(golden.getLines().get(0).getDurationSec()).isEqualTo(9.541);
        // writeTo：紧凑 UTF-8（中文不转义），回读等值
        Path out = Path.of("target", "timeline-calc-test", "audio_meta_roundtrip.json");
        Files.createDirectories(out.getParent());
        golden.writeTo(out);
        String written = Files.readString(out, StandardCharsets.UTF_8);
        assertThat(written).contains("我们先看这道题").doesNotContain("\\u");
        assertThat(JSON.<AudioMeta>readValue(out.toFile(), AudioMeta.class))
                .usingRecursiveComparison().isEqualTo(golden);
    }
}
