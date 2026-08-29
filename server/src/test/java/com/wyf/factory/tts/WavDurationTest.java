package com.wyf.factory.tts;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * WAV 时长解析：golden 样本 template/public/audio/lines/line_01.wav（仓库单源不复制），
 * 时长与 golden audio_meta.json 对应场景 durationSec ±0.002s（无 ffprobe，Global Constraint 8）。
 */
class WavDurationTest {

    private static byte[] goldenLine01;

    @BeforeAll
    static void loadGolden() throws IOException {
        goldenLine01 = Files.readAllBytes(repoRoot().resolve("template/public/audio/lines/line_01.wav"));
    }

    @Test
    @DisplayName("golden line_01.wav 时长与 golden audio_meta.json 场景值 ±0.002s")
    void goldenWavDuration_matchesAudioMeta() {
        double duration = WavDuration.durationSec(goldenLine01);
        double golden = goldenMetaLine1DurationSec();
        assertThat(duration).isCloseTo(golden, within(0.002));
    }

    @Test
    @DisplayName("byteRate = ch(22-23) × sampleRate(24-27) × bits(34-35) ÷ 8 = 48000")
    void byteRate_parsesHeader() {
        assertThat(WavDuration.byteRate(goldenLine01)).isEqualTo(48000);
    }

    @Test
    @DisplayName("时长 = 文件总字节数 ÷ byte_rate（头部 data-size 垃圾值不参与，Global Constraint 8）")
    void duration_usesTotalBytesNotDataSize() {
        // golden: 457964 字节 ÷ 48000 byte_rate = 9.5409…，哪怕 data-size 字段被改坏也不影响
        byte[] corruptedDataSize = goldenLine01.clone();
        corruptedDataSize[40] = (byte) 0xFF; // data-size 低字节改坏
        corruptedDataSize[41] = (byte) 0xFF;
        assertThat(WavDuration.durationSec(corruptedDataSize))
                .isCloseTo(WavDuration.durationSec(goldenLine01), within(1e-9));
    }

    @Test
    @DisplayName("非 RIFF/WAVE → IllegalArgumentException「不是合法 WAV」")
    void invalidMagic_rejected() {
        byte[] notWav = "NOTAWAVEFILE".getBytes(StandardCharsets.US_ASCII);
        assertThatThrownBy(() -> WavDuration.durationSec(notWav))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是合法 WAV");
    }

    @Test
    @DisplayName("不足 44 字节头 → IllegalArgumentException")
    void tooShort_rejected() {
        assertThatThrownBy(() -> WavDuration.durationSec(new byte[10]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是合法 WAV");
    }

    @Test
    @DisplayName("byte_rate ≤ 0 → IllegalArgumentException「byte_rate 异常」")
    void zeroByteRate_rejected() {
        byte[] broken = goldenLine01.clone();
        broken[24] = 0; // sampleRate 置 0 → byte_rate = 0
        broken[25] = 0;
        broken[26] = 0;
        broken[27] = 0;
        assertThatThrownBy(() -> WavDuration.byteRate(broken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("byte_rate 异常");
    }

    /** golden audio_meta.json lines[0].durationSec（单一事实源，直接读模板不复制）。 */
    private static double goldenMetaLine1DurationSec() {
        try {
            String json = Files.readString(
                    repoRoot().resolve("template/src/data/audio_meta.json"), StandardCharsets.UTF_8);
            int linesAt = json.indexOf("\"lines\"");
            int from = json.indexOf("\"durationSec\"", linesAt) + "\"durationSec\":".length();
            int to = json.indexOf(",", from);
            return Double.parseDouble(json.substring(from, to).trim());
        } catch (IOException e) {
            throw new IllegalStateException("golden audio_meta.json 读取失败", e);
        }
    }

    static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("template/src/data/audio_meta.json"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("仓库根（含 template/src/data/audio_meta.json）").isNotNull();
        return dir;
    }
}
