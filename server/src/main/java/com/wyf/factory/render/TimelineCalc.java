package com.wyf.factory.render;

import com.wyf.factory.tts.AudioMeta;

/**
 * 时间轴公式（template/scripts/pick_frames.mjs:8-25 逐行镜像，终审 §7 数据一致性）。
 *
 * <p>对照（mjs 行号 → 本类行号）：
 * <ul>
 *   <li>:8 FPS = meta.fps ?? 30</li>
 *   <li>:9 BREATH = Math.round((meta.breathSec ?? 0.18) * FPS)</li>
 *   <li>:13-14 t = act1Dur = Math.round(fixed.act1.durationSec * FPS) 起头</li>
 *   <li>:17-23 逐场 dur = Math.round(line.durationSec * FPS)，t += dur + BREATH</li>
 *   <li>:24 act5Start = t - BREATH（act5 起点抹掉末场后的气口）</li>
 *   <li>:25 total = act5Start + Math.round((fixed.act5.durationSec + (act5TailSec ?? 2.0)) * FPS)</li>
 * </ul>
 * meta.lines 与 content.scenes 同序（TtsPipeline 按 scenes 顺序写入），故逐 lines 即逐场。</p>
 */
public final class TimelineCalc {

    private static final int DEFAULT_FPS = 30;

    private TimelineCalc() {
    }

    /** 总帧数：act1 起 → 正文逐句（句间 0.18s 气口）→ act5（抹气口）→ 尾停 2s。 */
    public static int totalFrames(AudioMeta meta) {
        int fps = meta.getFps() > 0 ? meta.getFps() : DEFAULT_FPS;
        int breath = (int) Math.round(meta.getBreathSec() * fps);
        long t = Math.round(meta.getFixed().get("act1").getDurationSec() * fps);
        for (AudioMeta.LineEntry line : meta.getLines()) {
            long dur = Math.round(line.getDurationSec() * fps);
            t += dur + breath;
        }
        long act5Start = t - breath;
        long act5Frames = Math.round((meta.getFixed().get("act5").getDurationSec() + meta.getAct5TailSec()) * fps);
        return (int) (act5Start + act5Frames);
    }
}
