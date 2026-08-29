package com.wyf.factory.tts;

/**
 * 完整性判据（spec §12 + template/scripts/gen_tts_template.py tail_profile/is_complete 移植）。
 *
 * <p>逐条对照（Python 行号 → 本类行号）：
 * <ul>
 *   <li>:45-55 parse_pcm（扫 RIFF 块取 data 段，data-size 字段不可信）→ {@link #dataSamples}</li>
 *   <li>:57 rms = sqrt(sum(x²) / max(1, len)) → {@link #rms}</li>
 *   <li>:64 窗口 w = int(sr*0.08)、w3 = int(sr*0.24)（16bit 单声道样本数）→ {@link #isComplete}</li>
 *   <li>:65 last80 = rms(samples[-w:])、prev240 = rms(samples[-w-w3:-w])、
 *       prev480 = rms(samples[-w-2*w3:-w-w3]) → 同式</li>
 *   <li>:68 last80 &lt; 100 → complete（数字静音 = 自然收尾）</li>
 *   <li>:70 last80 &lt; 0.35*prev240 且 last80 &lt; 0.35*prev480（问句气音收尾不误杀）
 *       + spec §12 追加时长 ≥ longestTakeSec*0.92（防「提前断掉但尾部安静」的坏 take）</li>
 *   <li>:62-63 无样本 → (1e9,1e9,1e9) → 两分支都不过 → incomplete</li>
 * </ul></p>
 */
public final class RmsCheck {

    private static final double TAIL_WINDOW_SEC = 0.08;
    private static final double PREV_WINDOW_SEC = 0.24;
    private static final double SILENCE_RMS = 100.0;
    private static final double DECAY_RATIO = 0.35;
    private static final double MIN_DURATION_RATIO = 0.92;

    private RmsCheck() {
    }

    /**
     * 完整性判定：last80ms RMS &lt; 100（数字静音）→ 完整；
     * 否则（last80/prev240 &lt; 0.35 且 last80/prev480 &lt; 0.35 且
     * durationSec ≥ longestTakeSec*0.92）→ 完整；其余 → 截断。
     *
     * @param wav             合成产物 WAV 字节
     * @param durationSec     该 take 时长（WavDuration.durationSec）
     * @param longestTakeSec  本轮已成功的最长 take 时长（首轮无基准传 0 = 不设时长下限）
     */
    public static boolean isComplete(byte[] wav, double durationSec, double longestTakeSec) {
        short[] samples = dataSamples(wav);
        if (samples.length == 0) {
            return false;
        }
        int sampleRate = WavDuration.sampleRate(wav);
        int w = (int) (sampleRate * TAIL_WINDOW_SEC);
        int w3 = (int) (sampleRate * PREV_WINDOW_SEC);
        int n = samples.length;
        double last80 = rms(samples, n - w, n);
        double prev240 = rms(samples, Math.max(0, n - w - w3), n - w);
        double prev480 = rms(samples, Math.max(0, n - w - 2 * w3), n - w - w3);

        if (last80 < SILENCE_RMS) {
            return true;
        }
        return last80 < DECAY_RATIO * prev240
                && last80 < DECAY_RATIO * prev480
                && durationSec >= MIN_DURATION_RATIO * longestTakeSec;
    }

    /** 扫 RIFF 块找 data 段，返回 16bit 小端样本（parse_pcm 移植；找不到 → 空数组）。 */
    private static short[] dataSamples(byte[] wav) {
        int pos = 12;
        while (pos + 8 <= wav.length) {
            String chunkId = ascii(wav, pos, 4);
            int size = (wav[pos + 4] & 0xFF) | ((wav[pos + 5] & 0xFF) << 8)
                    | ((wav[pos + 6] & 0xFF) << 16) | ((wav[pos + 7] & 0xFF) << 24);
            if ("data".equals(chunkId)) {
                int from = pos + 8;
                int sampleCount = (wav.length - from) / 2;
                short[] pcm = new short[sampleCount];
                for (int i = 0; i < sampleCount; i++) {
                    pcm[i] = (short) ((wav[from + 2 * i] & 0xFF) | ((wav[from + 2 * i + 1] & 0xFF) << 8));
                }
                return pcm;
            }
            pos += 8 + size + (size & 1);
        }
        return new short[0];
    }

    /** sqrt(sum(x²) / max(1, len))（Python :57 逐字对齐；sum 用 long 精确累加）。 */
    private static double rms(short[] samples, int from, int to) {
        int lo = Math.max(0, from);
        int hi = Math.min(to, samples.length);
        if (hi <= lo) {
            return 0.0;
        }
        long sumSquares = 0;
        for (int i = lo; i < hi; i++) {
            int v = samples[i];
            sumSquares += (long) v * v;
        }
        return Math.sqrt(sumSquares / (double) Math.max(1, hi - lo));
    }

    private static String ascii(byte[] wav, int off, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) (wav[off + i] & 0xFF));
        }
        return sb.toString();
    }
}
