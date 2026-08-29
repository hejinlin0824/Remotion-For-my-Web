package com.wyf.factory.config;

import com.wyf.factory.glm.GlmException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Function;

/**
 * 密钥解析（Global Constraint 4）：env 优先 → secrets.local.yml 回落。
 * 不引 snakeyaml：手写两行式解析，只识别 glm.api-key / tts.api-key 两个前缀。
 * 不做 settings.json 等其它文件回落（T12 凭据外发 footgun 教训）。
 * key 零泄漏：不打日志，异常消息只含来源名，绝不含 key 值。
 */
@Component
public class Secrets {

    private static final String GLM_PREFIX = "glm.api-key:";
    private static final String TTS_PREFIX = "tts.api-key:";

    private final Function<String, String> envLookup;
    private final Path secretsFile;

    /** Spring 生产构造：真实环境变量 + 工作目录（server/）下的 secrets.local.yml。 */
    public Secrets() {
        this(System::getenv, Path.of("secrets.local.yml"));
    }

    /** 测试构造：env 读取抽象成 Function 注入（反射设 fake env 不可行）。 */
    public Secrets(Function<String, String> envLookup, Path secretsFile) {
        this.envLookup = envLookup;
        this.secretsFile = secretsFile;
    }

    /** GLM key：ZHIPU_API_KEY → ZHIPUAI_API_KEY → GLM_API_KEY → secrets.local.yml 的 glm.api-key。 */
    public String glmKey() {
        String key = firstEnv("ZHIPU_API_KEY", "ZHIPUAI_API_KEY", "GLM_API_KEY");
        if (key != null) {
            return key;
        }
        key = fileValue(GLM_PREFIX);
        if (key != null) {
            return key;
        }
        throw new GlmException("GLM key 未配置：设 ZHIPU_API_KEY 或 secrets.local.yml glm.api-key", false);
    }

    /** DashScope key：DASHSCOPE_API_KEY → secrets.local.yml 的 tts.api-key。 */
    public String ttsKey() {
        String key = firstEnv("DASHSCOPE_API_KEY");
        if (key != null) {
            return key;
        }
        key = fileValue(TTS_PREFIX);
        if (key != null) {
            return key;
        }
        throw new GlmException("DashScope key 未配置：设 DASHSCOPE_API_KEY 或 secrets.local.yml tts.api-key", false);
    }

    /** 依次找环境变量；空串/纯空白视为未设置；命中值 strip。 */
    private String firstEnv(String... names) {
        for (String name : names) {
            String value = envLookup.apply(name);
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /**
     * 两行式文件解析：逐行找指定前缀，trim 取值，支持成对引号包裹；
     * 文件不存在 = 无此来源（返回 null，不抛）。
     */
    private String fileValue(String prefix) {
        if (!Files.isRegularFile(secretsFile)) {
            return null;
        }
        try {
            for (String line : Files.readAllLines(secretsFile)) {
                String trimmed = line.strip();
                if (trimmed.startsWith("#") || !trimmed.startsWith(prefix)) {
                    continue;
                }
                String value = trimmed.substring(prefix.length()).strip();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
                    value = value.substring(1, value.length() - 1).strip();
                }
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } catch (IOException e) {
            throw new GlmException("secrets.local.yml 读取失败：" + e.getMessage(), false, e);
        }
        return null;
    }
}
