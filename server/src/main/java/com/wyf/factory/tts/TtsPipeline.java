package com.wyf.factory.tts;

import com.wyf.factory.config.AppProperties;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.GlmException;
import com.wyf.factory.render.TimelineCalc;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * TTS 批量管线（gen_tts_template.py main/synthesize_line 移植，Ruling-13 单 take）。
 *
 * <p>逐句 scenes[i].ttsText → linesDir/line_NN.wav（i 从 1 两位），每句 1 take + RmsCheck
 * 完整性判定：完整即采用；截断/异常删产物重试 ≤app.tts.maxAttemptsPerLine 次（异常计一次尝试）；
 * 每次尝试后 sleep app.tts.intervalMs 节流（脚本 :125 的 THROTTLE），期间出现过 429 则追加
 * app.tts.cooldownMs 冷却。某句全败 → 整批废弃重录 ≤1 次（删全部 line_*.wav 从头再来，
 * 禁单句补录防跨批音色漂移，Global Constraint 3）；再败 → {@link TtsFatalException}。</p>
 *
 * <p>fixed 两句（act1/act5）读 template/public/audio/fixed 既有 wav 字节数算时长，永不重合成
 * （Global Constraint 10）。最后调 {@link TimelineCalc} 算 totalFrames 写入 meta
 * （终审 §7 数据一致性）。信号量/串行控制归 T10 编排器，本类只保证句间 interval。</p>
 */
@Component
public class TtsPipeline {

    /** 致命失败：某句全部尝试耗尽且整批重录再败（状态机 → FAILED，Global Constraint 3）。 */
    public static final class TtsFatalException extends RuntimeException {
        public TtsFatalException(String message) {
            super(message);
        }

        public TtsFatalException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 可注入的睡眠（interval/冷却/退避）：单测 fake 免真等。 */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;

        Sleeper SYSTEM = millis -> Thread.sleep(millis);
    }

    private static final int BATCH_RETAKE_LIMIT = 1;
    private static final Pattern LINE_WAV = Pattern.compile("line_\\d{2}\\.wav");
    private static final String FIXED_VOICE = DashScopeTts.VOICE;
    private static final String FIXED_MODEL = DashScopeTts.MODEL;
    private static final double FIXED_RATE = 1.0;
    private static final int FIXED_FPS = 30;
    private static final double FIXED_BREATH_SEC = 0.18;
    private static final double FIXED_ACT5_TAIL_SEC = 2.0;

    private final DashScopeTts tts;
    private final AppProperties props;
    private final Sleeper sleeper;

    public TtsPipeline(DashScopeTts tts, AppProperties props) {
        this(tts, props, Sleeper.SYSTEM);
    }

    /** 测试构造：注入 fake sleep。 */
    public TtsPipeline(DashScopeTts tts, AppProperties props, Sleeper sleeper) {
        this.tts = tts;
        this.props = props;
        this.sleeper = sleeper;
    }

    /** 批量合成正文全部台词，产出 audio_meta（wav 写 linesDir，meta 由调用方 writeTo 落盘）。 */
    public AudioMeta synthesizeAll(ContentJson content, Path linesDir) {
        try {
            return synthesizeBatch(content, linesDir);
        } catch (TtsFatalException first) {
            deleteLineWavs(linesDir);
            try {
                return synthesizeBatch(content, linesDir);
            } catch (TtsFatalException second) {
                throw new TtsFatalException("整批废弃重录 1 次后仍有句子无法合成出完整音频 → FAILED（"
                        + first.getMessage() + "）", second);
            }
        }
    }

    private AudioMeta synthesizeBatch(ContentJson content, Path linesDir) {
        try {
            Files.createDirectories(linesDir);
        } catch (IOException e) {
            throw new UncheckedIOException("lines 目录创建失败：" + linesDir, e);
        }
        AudioMeta meta = new AudioMeta();
        meta.setVoice(FIXED_VOICE);
        meta.setModel(FIXED_MODEL);
        meta.setRate(FIXED_RATE);
        meta.setFps(FIXED_FPS);
        meta.setBreathSec(FIXED_BREATH_SEC);
        meta.setAct5TailSec(FIXED_ACT5_TAIL_SEC);
        meta.getFixed().put("act1", fixedEntry("act1"));
        meta.getFixed().put("act5", fixedEntry("act5"));

        double longestTakeSec = 0.0;
        List<ContentJson.Scene> scenes = content.scenes();
        for (int i = 0; i < scenes.size(); i++) {
            ContentJson.Scene scene = scenes.get(i);
            int index = i + 1;
            String fileName = String.format("line_%02d.wav", index);
            long rateLimitsBefore = tts.rateLimitEvents();
            double durationSec = synthesizeLine(scene.ttsText(), linesDir.resolve(fileName), longestTakeSec);
            if (tts.rateLimitEvents() > rateLimitsBefore) {
                sleepQuietly(props.getTts().getCooldownMs()); // 本句期间出现过 429 → 15s 冷却
            }
            longestTakeSec = Math.max(longestTakeSec, durationSec);
            sleepQuietly(props.getTts().getIntervalMs()); // 句间节流（脚本 :150，含末句后）
            AudioMeta.LineEntry entry = new AudioMeta.LineEntry();
            entry.setIndex(index);
            entry.setSceneId(scene.id());
            entry.setFile("audio/lines/" + fileName);
            entry.setDurationSec(durationSec);
            entry.setText(scene.ttsText());
            meta.getLines().add(entry);
        }
        meta.setTotalFrames(TimelineCalc.totalFrames(meta));
        return meta;
    }

    /**
     * 单 take（Ruling-13）：完整即采用写盘返回时长（成功路径不 sleep，与脚本一致——
     * 句间节流由批循环负责）；截断/异常计一次尝试，尝试后 interval 节流、429 追加冷却
     * （脚本 :125-126），≤maxAttemptsPerLine 次；全败抛 TtsFatalException。
     */
    private double synthesizeLine(String text, Path dest, double longestTakeSec) {
        int maxAttempts = props.getTts().getMaxAttemptsPerLine();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            long rateLimitsBefore = tts.rateLimitEvents();
            try {
                byte[] wav = tts.synthesize(text);
                double durationSec = WavDuration.durationSec(wav);
                if (RmsCheck.isComplete(wav, durationSec, longestTakeSec)) {
                    Files.write(dest, wav);
                    return round3(durationSec);
                }
                // 不完整：不写产物，直接计一次尝试
            } catch (IOException e) {
                throw new UncheckedIOException("wav 写入失败：" + dest, e);
            } catch (GlmException e) {
                // 异常计一次尝试；耗尽后进整批废弃重录
            }
            if (tts.rateLimitEvents() > rateLimitsBefore) {
                sleepQuietly(props.getTts().getCooldownMs());
            }
            sleepQuietly(props.getTts().getIntervalMs());
        }
        throw new TtsFatalException(
                "「" + brief(text) + "」" + maxAttempts + " 次尝试均未合成出完整音频");
    }

    /** fixed 幕时长：读模板既有 wav 字节数算出（不重合成，Global Constraint 10）。 */
    private AudioMeta.FixedEntry fixedEntry(String name) {
        Path wav = Path.of(props.getTemplateDir()).resolve("public/audio/fixed/" + name + ".wav");
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(wav);
        } catch (IOException e) {
            throw new UncheckedIOException("fixed wav 读取失败：" + wav, e);
        }
        AudioMeta.FixedEntry entry = new AudioMeta.FixedEntry();
        entry.setFile("audio/fixed/" + name + ".wav");
        entry.setDurationSec(round3(WavDuration.durationSec(bytes)));
        return entry;
    }

    /** 整批废弃：删 linesDir 全部 line_*.wav（fixed 与其它文件不动）。 */
    private void deleteLineWavs(Path linesDir) {
        if (!Files.isDirectory(linesDir)) {
            return;
        }
        try (Stream<Path> files = Files.list(linesDir)) {
            for (Path file : files.filter(p -> LINE_WAV.matcher(p.getFileName().toString()).matches())
                    .toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("整批废弃删除 line_*.wav 失败：" + linesDir, e);
        }
    }

    private void sleepQuietly(long millis) {
        try {
            sleeper.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TtsFatalException("TTS 管线节流被中断", e);
        }
    }

    /** 时长照脚本 round(dur, 3) 落 meta（golden 即此精度，TimelineCalc 消费同值）。 */
    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    private static String brief(String text) {
        return text.length() <= 18 ? text : text.substring(0, 18) + "…";
    }
}
