package com.wyf.factory.tts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wyf.factory.config.AppProperties;
import com.wyf.factory.config.Secrets;
import com.wyf.factory.content.ContentJson;
import com.wyf.factory.glm.HttpTransport;
import com.wyf.factory.render.TimelineCalc;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TTS 管线全流程（fake transport + fake AudioFetcher + fake sleep 注入，零 API 成本）：
 * 3 句全过 / 单句截断重试 → 整批废弃重录 / 整批再败 → TtsFatalException / 429 → 退避+冷却后继续。
 * fixed 两句时长读自临时模板目录的 wav 字节数（不重合成，Global Constraint 10）。
 */
class TtsPipelineTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String KEY = "test-key-do-not-leak";

    @TempDir
    Path tempDir;

    private Path linesDir;
    private Path templateDir;
    private FakeTransport transport;
    private RecordingSleeper sleeper;

    @BeforeEach
    void setUp() throws IOException {
        linesDir = tempDir.resolve("ws/public/audio/lines");
        templateDir = tempDir.resolve("template");
        // 临时模板 fixed 资产：act1=2.0s、act5=1.5s（字节算时长，不重合成）
        Path fixedDir = templateDir.resolve("public/audio/fixed");
        Files.createDirectories(fixedDir);
        Files.write(fixedDir.resolve("act1.wav"), completeWav(2.0));
        Files.write(fixedDir.resolve("act5.wav"), completeWav(1.5));
        transport = new FakeTransport();
        sleeper = new RecordingSleeper();
    }

    private TtsPipeline pipeline() {
        AppProperties props = new AppProperties();
        props.setTemplateDir(templateDir.toString());
        DashScopeTts tts = new DashScopeTts(transport, secrets(), props, 2000L, sleeper, audioFetcher());
        return new TtsPipeline(tts, props, sleeper);
    }

    private Secrets secrets() {
        return new Secrets(name -> KEY, tempDir.resolve("absent-secrets.local.yml"));
    }

    private DashScopeTts.AudioFetcher audioFetcher() {
        return url -> {
            byte[] wav = transport.audio.poll();
            if (wav == null) {
                throw new IOException("下载脚本耗尽：" + url);
            }
            return wav;
        };
    }

    private static ContentJson contentOfThreeScenes() {
        return new ContentJson(null, null, null, null, null, null, List.of(
                new ContentJson.Scene("s01", 2, "problem-card", "第一句台词", Map.of()),
                new ContentJson.Scene("s02", 3, "step-card", "第二句台词", Map.of()),
                new ContentJson.Scene("s03", 4, "checklist-card", "第三句台词", Map.of())));
    }

    /** 2.0s/2.5s/2.2s 完整 wav（尾 80ms 数字静音）：字节算时长 round3 = 2.001/2.501/2.201。 */
    private byte[] completeWav(double sec) {
        return RmsCheckTest.syntheticWav(24000, sec, 0, 8000);
    }

    /** 尾部满能量 = 服务端截断形态：last80>100 且无衰减 → incomplete。 */
    private byte[] truncatedWav(double sec) {
        return RmsCheckTest.syntheticWav(24000, sec, 8000, 8000);
    }

    private void given(String text, byte[] wav) {
        transport.given(text, wav);
    }

    private void givenError(String text, int status) {
        transport.given(text, status);
    }

    @Test
    @DisplayName("3 句全过 → linesDir 3 个 wav + meta 写出（含 totalFrames）+ durationSec/totalFrames 一致")
    void allLinesComplete_metaAndFilesWritten() throws IOException {
        given("第一句台词", completeWav(2.0));
        given("第二句台词", completeWav(2.5));
        given("第三句台词", completeWav(2.2));
        ContentJson content = contentOfThreeScenes();

        AudioMeta meta = pipeline().synthesizeAll(content, linesDir);

        // wav 落盘逐字节一致
        assertThat(linesDir.resolve("line_01.wav")).hasBinaryContent(completeWav(2.0));
        assertThat(linesDir.resolve("line_02.wav")).hasBinaryContent(completeWav(2.5));
        assertThat(linesDir.resolve("line_03.wav")).hasBinaryContent(completeWav(2.2));
        try (var files = Files.list(linesDir)) {
            assertThat(files.filter(p -> p.getFileName().toString().endsWith(".wav"))).hasSize(3);
        }
        // meta 常量字段照 golden 键名
        assertThat(meta.getVoice()).isEqualTo("Cherry");
        assertThat(meta.getModel()).isEqualTo("qwen-tts");
        assertThat(meta.getRate()).isEqualTo(1.0);
        assertThat(meta.getFps()).isEqualTo(30);
        assertThat(meta.getBreathSec()).isEqualTo(0.18);
        assertThat(meta.getAct5TailSec()).isEqualTo(2.0);
        // fixed：读模板 wav 字节数算出（2.0s→2.001、1.5s→1.501），file 照 golden 形态
        assertThat(meta.getFixed().get("act1").getFile()).isEqualTo("audio/fixed/act1.wav");
        assertThat(meta.getFixed().get("act1").getDurationSec()).isEqualTo(2.001);
        assertThat(meta.getFixed().get("act5").getFile()).isEqualTo("audio/fixed/act5.wav");
        assertThat(meta.getFixed().get("act5").getDurationSec()).isEqualTo(1.501);
        // lines：index/sceneId/file/durationSec/text 逐条
        assertThat(meta.getLines()).hasSize(3);
        AudioMeta.LineEntry l1 = meta.getLines().get(0);
        assertThat(l1.getIndex()).isEqualTo(1);
        assertThat(l1.getSceneId()).isEqualTo("s01");
        assertThat(l1.getFile()).isEqualTo("audio/lines/line_01.wav");
        assertThat(l1.getDurationSec()).isEqualTo(2.001);
        assertThat(l1.getText()).isEqualTo("第一句台词");
        assertThat(meta.getLines().get(1).getDurationSec()).isEqualTo(2.501);
        assertThat(meta.getLines().get(2).getDurationSec()).isEqualTo(2.201);
        // totalFrames 与 TimelineCalc 一致，且 meta 写出后仍在
        assertThat(meta.getTotalFrames()).isEqualTo(TimelineCalc.totalFrames(meta));
        Path metaOut = tempDir.resolve("ws/src/data/audio_meta.json");
        Files.createDirectories(metaOut.getParent());
        meta.writeTo(metaOut);
        AudioMeta reread = JSON.readValue(metaOut.toFile(), AudioMeta.class);
        assertThat(reread.getTotalFrames()).isEqualTo(meta.getTotalFrames());
        assertThat(reread.getLines()).hasSize(3);
        // 请求形状：DashScope 端点、Bearer key、model/voice 逐字
        assertThat(transport.requests).hasSize(3);
        assertThat(transport.requests.get(0).uri().toString())
                .isEqualTo("https://dashscope.aliyuncs.com/api/v1/services/aigc/multimodal-generation/generation");
        assertThat(transport.requests.get(0).headers().firstValue("Authorization")).contains("Bearer " + KEY);
        assertThat(transport.requests.get(0).headers().firstValue("Content-Type")).contains("application/json");
        JsonNode body = JSON.readTree(transport.bodies.get(0));
        assertThat(body.path("model").asText()).isEqualTo("qwen-tts");
        assertThat(body.path("input").path("voice").asText()).isEqualTo("Cherry");
        assertThat(body.path("input").path("text").asText()).isEqualTo("第一句台词");
        assertThat(transport.texts).containsExactly("第一句台词", "第二句台词", "第三句台词");
    }

    @Test
    @DisplayName("第 2 句连续截断 3 次 → 该句重试 → 整批废弃重录（两轮完整句序）成功")
    void lineTruncated_batchRetakeSucceeds() throws IOException {
        given("第一句台词", completeWav(2.0));
        given("第一句台词", completeWav(2.0));
        given("第二句台词", truncatedWav(2.5));
        given("第二句台词", truncatedWav(2.5));
        given("第二句台词", truncatedWav(2.5));
        given("第二句台词", completeWav(2.5));
        given("第三句台词", completeWav(2.2));

        AudioMeta meta = pipeline().synthesizeAll(contentOfThreeScenes(), linesDir);

        // 两轮完整句序：轮1 L1,L2,L2,L2 → 全败；删产物从头再来 → 轮2 L1,L2,L3
        assertThat(transport.texts).containsExactly(
                "第一句台词", "第二句台词", "第二句台词", "第二句台词",
                "第一句台词", "第二句台词", "第三句台词");
        assertThat(transport.audio).isEmpty();
        assertThat(meta.getLines().get(1).getDurationSec()).isEqualTo(2.501);
        assertThat(linesDir.resolve("line_01.wav")).hasBinaryContent(completeWav(2.0));
        assertThat(linesDir.resolve("line_03.wav")).hasBinaryContent(completeWav(2.2));
    }

    @Test
    @DisplayName("第 2 句两轮各 3 次仍截断 → TtsFatalException（fixed 永不重合成）")
    void batchRetakeStillTruncated_throwsFatal() {
        for (int i = 0; i < 2; i++) {
            given("第一句台词", completeWav(2.0)); // 两轮各 1 次
            for (int j = 0; j < 3; j++) {
                given("第二句台词", truncatedWav(2.5)); // 两轮各 3 次
            }
        }

        assertThatThrownBy(() -> pipeline().synthesizeAll(contentOfThreeScenes(), linesDir))
                .isInstanceOf(TtsPipeline.TtsFatalException.class)
                .hasMessageContaining("第二句台词");
        assertThat(transport.texts).containsExactly(
                "第一句台词", "第二句台词", "第二句台词", "第二句台词",
                "第一句台词", "第二句台词", "第二句台词", "第二句台词");
        assertThat(transport.texts).doesNotContain("第三句台词");
    }

    @Test
    @DisplayName("429 → 客户端退避 2s + 管线 15s 冷却后继续，整批成功")
    void rateLimited_backoffAndCooldown_thenContinue() throws IOException {
        givenError("第一句台词", 429);
        given("第一句台词", completeWav(2.0));
        given("第二句台词", completeWav(2.5));
        given("第三句台词", completeWav(2.2));

        AudioMeta meta = pipeline().synthesizeAll(contentOfThreeScenes(), linesDir);

        assertThat(meta.getLines()).hasSize(3);
        assertThat(transport.texts).containsExactly("第一句台词", "第一句台词", "第二句台词", "第三句台词");
        // 退避（DashScopeTts 2s）与冷却（TtsPipeline 15s）都发生且未真睡；3 次句间节流
        assertThat(sleeper.sleeps).contains(2000L);
        assertThat(sleeper.sleeps).contains(15000L);
        assertThat(sleeper.sleeps.stream().filter(m -> m == 3000L).count()).isEqualTo(3L);
    }

    @Test
    @DisplayName("句间节流：每句成功后 sleep app.tts.intervalMs（3000，照 gen_tts_template.py :150）")
    void intervalThrottle_betweenLines() throws IOException {
        given("第一句台词", completeWav(2.0));
        given("第二句台词", completeWav(2.5));
        given("第三句台词", completeWav(2.2));

        pipeline().synthesizeAll(contentOfThreeScenes(), linesDir);

        assertThat(sleeper.sleeps).containsOnly(3000L);
        assertThat(sleeper.sleeps).hasSize(3);
    }

    /** 脚本化 fake transport：按 ttsText 分发响应（Integer=错误状态码，byte[]=成功 wav），记录请求序。 */
    private static final class FakeTransport implements HttpTransport {
        final List<HttpRequest> requests = new ArrayList<>();
        final List<String> texts = new ArrayList<>();
        final List<byte[]> bodies = new ArrayList<>();
        final Deque<byte[]> audio = new ArrayDeque<>();
        private final Map<String, Deque<Object>> script = new HashMap<>();

        void given(String text, byte[] wav) {
            script.computeIfAbsent(text, k -> new ArrayDeque<>()).add(wav);
        }

        void given(String text, int errorStatus) {
            script.computeIfAbsent(text, k -> new ArrayDeque<>()).add(errorStatus);
        }

        @Override
        public HttpResponse<byte[]> send(HttpRequest request, byte[] body) throws IOException {
            requests.add(request);
            JsonNode json = JSON.readTree(body);
            String text = json.path("input").path("text").asText();
            texts.add(text);
            bodies.add(body);
            Object next = script.containsKey(text) ? script.get(text).poll() : null;
            if (next == null) {
                throw new IOException("transport 脚本耗尽：" + text);
            }
            if (next instanceof Integer status) {
                return response(status, "{\"code\":\"Throttling.RateQuota\",\"message\":\"flow control\"}");
            }
            String url = "https://fake.dashscope.test/audio/" + texts.size() + ".wav";
            audio.add((byte[]) next);
            return response(200, "{\"output\":{\"audio\":{\"url\":\"" + url + "\"},\"usage\":{}}}");
        }
    }

    private static final class RecordingSleeper implements TtsPipeline.Sleeper {
        final List<Long> sleeps = new ArrayList<>();

        @Override
        public void sleep(long millis) {
            sleeps.add(millis);
        }
    }

    private static HttpResponse<byte[]> response(int status, String body) {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        URI uri = URI.create("https://fake.dashscope.test/irrelevant");
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return HttpRequest.newBuilder(uri).build(); }
            @Override public Optional<HttpResponse<byte[]>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            @Override public byte[] body() { return bytes; }
            @Override public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return uri; }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_2; }
        };
    }
}
