package com.wyf.factory.tts;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 完整性判据（gen_tts_template.py tail_profile/is_complete + spec §12 移植）一正一反：
 * 完整样本（golden line_01.wav，尾部数字静音）→ complete；
 * 截断样本（golden 砍尾 40%）→ incomplete；
 * 另构造合成样本钉死「时长 ≥ 最长 take 92%」问句气音分支的两个方向。
 */
class RmsCheckTest {

    private static byte[] goldenLine01;

    @BeforeAll
    static void loadGolden() throws IOException {
        goldenLine01 = Files.readAllBytes(WavDurationTest.repoRoot()
                .resolve("template/public/audio/lines/line_01.wav"));
    }

    @Test
    @DisplayName("golden 完整样本（尾 80ms RMS≈0 数字静音）→ complete")
    void goldenSample_tailSilence_complete() {
        double dur = WavDuration.durationSec(goldenLine01);
        assertThat(RmsCheck.isComplete(goldenLine01, dur, dur)).isTrue();
    }

    @Test
    @DisplayName("截断样本（golden 砍尾 40%）→ incomplete（绝对静音与衰减两分支都不过）")
    void truncatedSample_incomplete() {
        byte[] truncated = truncateTail(goldenLine01, 0.60);
        double dur = WavDuration.durationSec(truncated);
        double longest = WavDuration.durationSec(goldenLine01);
        assertThat(RmsCheck.isComplete(truncated, dur, longest)).isFalse();
        // 纵使把「最长 take」基准拉到与截断时长一致（时长分支恒过），衰减比也不过 → 仍判截断
        assertThat(RmsCheck.isComplete(truncated, dur, dur)).isFalse();
    }

    @Test
    @DisplayName("问句气音收尾：衰减比 <0.35 且时长 ≥92% 最长 take → complete")
    void breathTail_decayAndLongEnough_complete() {
        // 尾 80ms RMS≈300（>100，非数字静音），前 240ms/前 480ms RMS≈3000（衰减比 0.1<0.35）
        byte[] breathTail = syntheticWav(24000, 1.0, 300, 3000);
        double dur = WavDuration.durationSec(breathTail);
        assertThat(RmsCheck.isComplete(breathTail, dur, dur)).isTrue();
    }

    @Test
    @DisplayName("同款气音收尾但时长 <92% 最长 take → incomplete（时长守卫防「提前断掉但尾部安静」）")
    void breathTail_tooShortVsLongest_incomplete() {
        byte[] breathTail = syntheticWav(24000, 1.0, 300, 3000);
        double dur = WavDuration.durationSec(breathTail);
        assertThat(RmsCheck.isComplete(breathTail, dur, dur / 0.8)).isFalse();
    }

    @Test
    @DisplayName("无 data 块/无样本 → incomplete（tail_profile 返回 1e9 的语义）")
    void noDataChunk_incomplete() {
        assertThat(RmsCheck.isComplete(new byte[44], 0.0, 0.0)).isFalse();
    }

    /** 砍掉尾部 keep 比例之外的字节（偶数对齐保持 16bit 样本完整）。 */
    static byte[] truncateTail(byte[] wav, double keep) {
        int cut = (int) (wav.length * keep);
        cut -= cut % 2;
        return Arrays.copyOf(wav, cut);
    }

    /**
     * 合成 16bit 单声道 WAV：全段恒幅 mainAmp，仅尾 80ms 恒幅 tailAmp——
     * last80 = tailAmp、prev240 = prev480 = mainAmp，用于钉死衰减比与时长两个判据。
     */
    static byte[] syntheticWav(int sampleRate, double sec, int tailAmp, int mainAmp) {
        int total = (int) (sampleRate * sec);
        int tail = (int) (sampleRate * 0.08);
        short[] pcm = new short[total];
        for (int i = 0; i < total; i++) {
            pcm[i] = (short) (i >= total - tail ? tailAmp : mainAmp);
        }
        return toWav(pcm, sampleRate);
    }

    /** 44 字节标准 PCM 头 + 小端 16bit 样本。 */
    static byte[] toWav(short[] pcm, int sampleRate) {
        int dataBytes = pcm.length * 2;
        byte[] out = new byte[44 + dataBytes];
        putAscii(out, 0, "RIFF");
        putI32(out, 4, 36 + dataBytes);
        putAscii(out, 8, "WAVE");
        putAscii(out, 12, "fmt ");
        putI32(out, 16, 16);
        putI16(out, 20, 1);            // PCM
        putI16(out, 22, 1);            // 单声道
        putI32(out, 24, sampleRate);
        putI32(out, 28, sampleRate * 2);
        putI16(out, 32, 2);            // block align
        putI16(out, 34, 16);           // bits per sample
        putAscii(out, 36, "data");
        putI32(out, 40, dataBytes);
        for (int i = 0; i < pcm.length; i++) {
            out[44 + 2 * i] = (byte) (pcm[i] & 0xFF);
            out[45 + 2 * i] = (byte) ((pcm[i] >> 8) & 0xFF);
        }
        return out;
    }

    private static void putAscii(byte[] dst, int off, String s) {
        for (int i = 0; i < s.length(); i++) {
            dst[off + i] = (byte) s.charAt(i);
        }
    }

    private static void putI16(byte[] dst, int off, int v) {
        dst[off] = (byte) (v & 0xFF);
        dst[off + 1] = (byte) ((v >> 8) & 0xFF);
    }

    private static void putI32(byte[] dst, int off, int v) {
        putI16(dst, off, v & 0xFFFF);
        putI16(dst, off + 2, (v >> 16) & 0xFFFF);
    }
}
