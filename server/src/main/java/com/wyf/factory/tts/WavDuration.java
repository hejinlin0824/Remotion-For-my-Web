package com.wyf.factory.tts;

/**
 * WAV 时长解析（template/scripts/gen_tts_template.py:34-43 wav_duration 逐字移植）。
 *
 * <p>无 ffprobe（Global Constraint 8）：时长 = 文件总字节数 ÷ byte_rate。
 * 头部 data-size 字段是 INT32_MAX 垃圾值不可信，绝不参与计算；
 * byte_rate 也不读 28-31 的字段，照脚本由 ch × sampleRate × bits ÷ 8 现算。</p>
 */
public final class WavDuration {

    private static final int HEADER_BYTES = 44;

    private WavDuration() {
    }

    /** byte_rate = ch(byte22-23) × sampleRate(byte24-27) × bits(byte34-35) ÷ 8（照脚本现算）。 */
    public static int byteRate(byte[] wav) {
        validateWav(wav);
        int channels = u16(wav, 22);
        int sampleRate = u32(wav, 24);
        int bitsPerSample = u16(wav, 34);
        int byteRate = channels * sampleRate * bitsPerSample / 8;
        if (byteRate <= 0) {
            throw new IllegalArgumentException("byte_rate 异常");
        }
        return byteRate;
    }

    /** sampleRate（byte24-27）：RmsCheck 取 80ms/240ms 窗口用。 */
    public static int sampleRate(byte[] wav) {
        validateWav(wav);
        return u32(wav, 24);
    }

    /** 时长秒 = 文件总字节数 ÷ byte_rate（Python len(raw) / byte_rate 逐字对齐）。 */
    public static double durationSec(byte[] wav) {
        return wav.length / (double) byteRate(wav);
    }

    private static void validateWav(byte[] wav) {
        if (wav.length < HEADER_BYTES || !hasMagic(wav, 0, "RIFF") || !hasMagic(wav, 8, "WAVE")) {
            throw new IllegalArgumentException("不是合法 WAV");
        }
    }

    private static boolean hasMagic(byte[] wav, int off, String magic) {
        for (int i = 0; i < magic.length(); i++) {
            if (wav[off + i] != (byte) magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int u16(byte[] wav, int off) {
        return (wav[off] & 0xFF) | ((wav[off + 1] & 0xFF) << 8);
    }

    private static int u32(byte[] wav, int off) {
        return (wav[off] & 0xFF)
                | ((wav[off + 1] & 0xFF) << 8)
                | ((wav[off + 2] & 0xFF) << 16)
                | ((wav[off + 3] & 0xFF) << 24);
    }
}
