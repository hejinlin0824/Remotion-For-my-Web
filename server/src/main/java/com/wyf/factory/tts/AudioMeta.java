package com.wyf.factory.tts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * audio_meta.json 契约 POJO——字段名/键序与 template/src/data/audio_meta.json（golden）逐字对齐：
 * voice/model/rate/fps/breathSec/act5TailSec/fixed{act1,act5}/lines[]，另按终审 §7 数据一致性
 * 追加 totalFrames（TimelineCalc 产物；模板侧 buildTimeline 自算同值，只作 Java 侧对拍锚）。
 * fixed/lines 的 file 值照 golden 形态：audio/fixed/act1.wav、audio/lines/line_01.wav（仓库相对）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AudioMeta {

    private String voice;
    private String model;
    private double rate;
    private int fps;
    private double breathSec;
    private double act5TailSec;
    /** LinkedHashMap：序列化保持 act1 → act5 键序。 */
    private Map<String, FixedEntry> fixed = new LinkedHashMap<>();
    private List<LineEntry> lines = new ArrayList<>();
    private int totalFrames;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 固定幕条目（act1/act5）：模板资产，只读时长不重合成（Global Constraint 10）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FixedEntry {
        private String file;
        private double durationSec;

        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public double getDurationSec() { return durationSec; }
        public void setDurationSec(double durationSec) { this.durationSec = durationSec; }
    }

    /** 正文台词条目：index 从 1 起，file=audio/lines/line_NN.wav。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class LineEntry {
        private int index;
        private String sceneId;
        private String file;
        private double durationSec;
        private String text;

        public int getIndex() { return index; }
        public void setIndex(int index) { this.index = index; }
        public String getSceneId() { return sceneId; }
        public void setSceneId(String sceneId) { this.sceneId = sceneId; }
        public String getFile() { return file; }
        public void setFile(String file) { this.file = file; }
        public double getDurationSec() { return durationSec; }
        public void setDurationSec(double durationSec) { this.durationSec = durationSec; }
        public String getText() { return text; }
        public void setText(String text) { this.text = text; }
    }

    /** 紧凑 JSON（UTF-8、中文不转义）写出，供 WorkspaceManager 覆写 workspace 副本。 */
    public void writeTo(Path audioMetaJson) {
        try {
            Files.writeString(audioMetaJson, MAPPER.writeValueAsString(this), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("audio_meta.json 写出失败：" + audioMetaJson, e);
        }
    }

    public String getVoice() { return voice; }
    public void setVoice(String voice) { this.voice = voice; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public double getRate() { return rate; }
    public void setRate(double rate) { this.rate = rate; }
    public int getFps() { return fps; }
    public void setFps(int fps) { this.fps = fps; }
    public double getBreathSec() { return breathSec; }
    public void setBreathSec(double breathSec) { this.breathSec = breathSec; }
    public double getAct5TailSec() { return act5TailSec; }
    public void setAct5TailSec(double act5TailSec) { this.act5TailSec = act5TailSec; }
    public Map<String, FixedEntry> getFixed() { return fixed; }
    public void setFixed(Map<String, FixedEntry> fixed) { this.fixed = fixed; }
    public List<LineEntry> getLines() { return lines; }
    public void setLines(List<LineEntry> lines) { this.lines = lines; }
    public int getTotalFrames() { return totalFrames; }
    public void setTotalFrames(int totalFrames) { this.totalFrames = totalFrames; }
}
