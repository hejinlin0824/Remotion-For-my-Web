package com.wyf.factory.tts;

import com.wyf.factory.config.AppProperties;
import com.wyf.factory.config.Secrets;
import com.wyf.factory.glm.JdkHttpTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真调 DashScope 的慢测试：只在有真实 key 时手动 `mvn -Pslow test -Dtest=TtsPipelineSlowIT` 跑
 * （Global Constraint 9）。产物落 target/tts-slow/，不入库。
 */
@Tag("slow")
class TtsPipelineSlowIT {

    @Test
    @DisplayName("真调 synthesize 1 句：已知函数 f(x)=x^2 → wav 产出且 RmsCheck 完整")
    void synthesize_realCall_producesCompleteWav() throws Exception {
        assumeTrue(hasKey(), "无 DashScope key（DASHSCOPE_API_KEY / secrets.local.yml tts.api-key），跳过真调");

        DashScopeTts tts = new DashScopeTts(
                new JdkHttpTransport(new AppProperties()), new Secrets(), new AppProperties());

        byte[] wav = tts.synthesize("已知函数 f(x)=x^2");

        double durationSec = WavDuration.durationSec(wav);
        boolean complete = RmsCheck.isComplete(wav, durationSec, durationSec);
        // 产物落 target（不入库），供人工试听
        Path out = Path.of("target", "tts-slow", "line_slow.wav");
        Files.createDirectories(out.getParent());
        Files.write(out, wav);
        System.out.println("[tts-slow] durationSec=" + durationSec + " isComplete=" + complete
                + " bytes=" + wav.length + " → " + out.toAbsolutePath());

        assertThat(wav.length).isGreaterThan(44);
        assertThat(durationSec).isGreaterThan(0.5);
        assertThat(complete).as("单 take 服务端截断偶发；重跑本测试即可，先例完整率约 30-70%").isTrue();
    }

    private static boolean hasKey() {
        if (System.getenv("DASHSCOPE_API_KEY") != null && !System.getenv("DASHSCOPE_API_KEY").isBlank()) {
            return true;
        }
        return Files.isRegularFile(Path.of("secrets.local.yml"));
    }
}
