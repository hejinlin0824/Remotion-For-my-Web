package com.wyf.factory.config;

import com.wyf.factory.glm.GlmException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretsTest {

    private static final String SECRET = "sk-file-secret-value";

    @TempDir
    Path tempDir;

    /** 每次调用用独立文件名：同测试方法内多次调用互不残留。 */
    private int fileSeq;

    /** env 查找注入为 map（反射设 fake env 不可行——brief 指定 Function 构造注入）。 */
    private Secrets secrets(Map<String, String> env, String fileContent) throws Exception {
        Path file = tempDir.resolve("secrets-" + (fileSeq++) + ".local.yml");
        if (fileContent != null) {
            Files.writeString(file, fileContent);
        }
        return new Secrets(env::get, file);
    }

    @Test
    @DisplayName("glmKey：env 优先级 ZHIPU_API_KEY > ZHIPUAI_API_KEY > GLM_API_KEY")
    void glmKey_envPriority() throws Exception {
        Map<String, String> all = Map.of(
                "ZHIPU_API_KEY", "k1", "ZHIPUAI_API_KEY", "k2", "GLM_API_KEY", "k3");
        assertThat(secrets(all, null).glmKey()).isEqualTo("k1");

        Map<String, String> noFirst = Map.of("ZHIPUAI_API_KEY", "k2", "GLM_API_KEY", "k3");
        assertThat(secrets(noFirst, null).glmKey()).isEqualTo("k2");

        assertThat(secrets(Map.of("GLM_API_KEY", "k3"), null).glmKey()).isEqualTo("k3");
    }

    @Test
    @DisplayName("glmKey：env 值首尾空白被 strip")
    void glmKey_envValueStripped() throws Exception {
        assertThat(secrets(Map.of("ZHIPU_API_KEY", "  k1  \n"), null).glmKey()).isEqualTo("k1");
    }

    @Test
    @DisplayName("glmKey：env 空串视为未设置，回落文件")
    void glmKey_blankEnvFallsToFile() throws Exception {
        String yml = "glm.api-key: " + SECRET + "\n";
        assertThat(secrets(Map.of("ZHIPU_API_KEY", "   "), yml).glmKey()).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("glmKey：env 全缺时回落 secrets.local.yml（引号/无引号均取值）")
    void glmKey_fileFallback() throws Exception {
        String yml = "other: x\n"
                + "# glm.api-key: 注释行不算\n"
                + "glm.api-key: \"" + SECRET + "\"\n"
                + "tts.api-key: 'tt-key'\n";
        Secrets s = secrets(Map.of(), yml);
        assertThat(s.glmKey()).isEqualTo(SECRET);
        assertThat(s.ttsKey()).isEqualTo("tt-key");
    }

    @Test
    @DisplayName("ttsKey：env DASHSCOPE_API_KEY 优先于文件 tts.api-key")
    void ttsKey_envThenFile() throws Exception {
        String yml = "tts.api-key: " + SECRET + "\n";
        assertThat(secrets(Map.of("DASHSCOPE_API_KEY", "env-tts"), yml).ttsKey()).isEqualTo("env-tts");
        assertThat(secrets(Map.of(), yml).ttsKey()).isEqualTo(SECRET);
    }

    @Test
    @DisplayName("env 优先于 secrets.local.yml 文件值")
    void envWinsOverFile() throws Exception {
        String yml = "glm.api-key: " + SECRET + "\ntts.api-key: " + SECRET + "\n";
        assertThat(secrets(Map.of("ZHIPU_API_KEY", "env-glm"), yml).glmKey()).isEqualTo("env-glm");
    }

    @Test
    @DisplayName("文件不存在 = 无此来源（不抛 IO），env 也没有时抛不可重试 GlmException")
    void missingFileAndEnv_throwsNonRetryable() throws Exception {
        Secrets s = secrets(Map.of(), null);

        assertThatThrownBy(s::glmKey)
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("ZHIPU_API_KEY")
                .hasMessageContaining("glm.api-key")
                .extracting("retryable")
                .isEqualTo(false);

        assertThatThrownBy(s::ttsKey)
                .isInstanceOf(GlmException.class)
                .hasMessageContaining("DASHSCOPE_API_KEY")
                .hasMessageContaining("tts.api-key");
    }

    @Test
    @DisplayName("key 零泄漏：异常消息只含来源名，绝不含 key 值")
    void exceptionMessage_neverContainsKeyValue() throws Exception {
        // 文件里只有 tts key：glmKey 失败路径的消息不得带出文件中的值
        String yml = "tts.api-key: " + SECRET + "\n";
        assertThatThrownBy(() -> secrets(Map.of(), yml).glmKey())
                .isInstanceOf(GlmException.class)
                .hasMessageNotContaining(SECRET);

        // env 里有关联值时，ttsKey 失败路径同样不得带出
        assertThatThrownBy(() -> secrets(Map.of("ZHIPU_API_KEY", "env-glm"), null).ttsKey())
                .isInstanceOf(GlmException.class)
                .hasMessageNotContaining("env-glm");
    }

    @Test
    @DisplayName("文件存在但无目标行：等同无此来源，抛未配置异常")
    void fileWithoutTargetLine_fallsThrough() throws Exception {
        String yml = "glm:\n  other-key: zz\n";
        Secrets s = secrets(Map.of(), yml);
        assertThatThrownBy(s::glmKey).isInstanceOf(GlmException.class);
    }
}
