package com.wyf.factory.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.* 配置绑定（application.yml / secrets.local.yml 覆盖）。
 * 字段树与 application.yml 一一对应：路径、渲染、GLM、TTS、QA、内容重试。
 */
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 封版模板目录（相对 server/ 运行目录，只读） */
    private String templateDir = "../template";
    /** 任务沙箱目录（模板副本） */
    private String workspaceDir = "../workspace";
    /** 成片与 QA 产物目录（保留） */
    private String artifactsDir = "../artifacts";
    /** 对外基地址（DONE 回调 videoUrl 前缀；反代/域名部署时在 secrets.local.yml 覆盖） */
    private String publicBaseUrl = "http://localhost:8080";

    private final Render render = new Render();
    private final Glm glm = new Glm();
    private final Tts tts = new Tts();
    private final Qa qa = new Qa();
    private final Retry retry = new Retry();

    /** 渲染资源配置 */
    public static class Render {
        /** 单次渲染超时（分钟） */
        private int timeoutMinutes = 30;
        /** 渲染并发上限 */
        private int concurrency = 2;

        public int getTimeoutMinutes() { return timeoutMinutes; }
        public void setTimeoutMinutes(int timeoutMinutes) { this.timeoutMinutes = timeoutMinutes; }
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
    }

    /** GLM 内容工位资源配置 */
    public static class Glm {
        private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
        private String model = "glm-5.3-flash";
        private int concurrency = 2;
        /** 单请求超时（秒）：健康长生成 ~62s，深夜降级窗口 120s 偏紧（08-30/08-31 两夜实证），放宽到 300 */
        private int timeoutSeconds = 300;
        /**
         * max_tokens 上限：thinking 始终开启且与正文共享此预算（官方文档），
         * GLM-5.3 最大输出 128K；默认 32K ≈ 观测峰值（~10K）3 倍余量，又约束失控长跑的时延。
         */
        private int maxTokens = 32768;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public int getConcurrency() { return concurrency; }
        public void setConcurrency(int concurrency) { this.concurrency = concurrency; }
        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
        public int getMaxTokens() { return maxTokens; }
        public void setMaxTokens(int maxTokens) { this.maxTokens = maxTokens; }
    }

    /** DashScope TTS 资源配置 */
    public static class Tts {
        /** 句间最小间隔（毫秒） */
        private long intervalMs = 3000;
        /** 429 退避冷却（毫秒） */
        private long cooldownMs = 15000;
        /** 每句最大合成尝试次数 */
        private int maxAttemptsPerLine = 3;

        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public long getCooldownMs() { return cooldownMs; }
        public void setCooldownMs(long cooldownMs) { this.cooldownMs = cooldownMs; }
        public int getMaxAttemptsPerLine() { return maxAttemptsPerLine; }
        public void setMaxAttemptsPerLine(int maxAttemptsPerLine) { this.maxAttemptsPerLine = maxAttemptsPerLine; }
    }

    /** 审帧 QA 配置 */
    public static class Qa {
        private int maxRounds = 5;

        public int getMaxRounds() { return maxRounds; }
        public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    }

    /** 内容工位重试配置 */
    public static class Retry {
        private int contentMax = 3;
        /** GENERATING 墙钟死线（分钟，T15b②）：进 GENERATING 落库 now+值，重试撞线直接判死 */
        private long genDeadlineMinutes = 30;
        /**
         * 全局处理墙钟（分钟，T21）：超线即 FAILED 作废，QUEUED 不计时，回环不刷新。
         * 首次进入 EXTRACTING 落库 now+值，此后绝对死线——健康路径 ~30min 一遍过，
         * 磨到 60min+ 的版本不可要，与既有 per-stage 上限叠加为全局兜底。
         */
        private long wallClockDeadlineMinutes = 60;

        public int getContentMax() { return contentMax; }
        public void setContentMax(int contentMax) { this.contentMax = contentMax; }
        public long getGenDeadlineMinutes() { return genDeadlineMinutes; }
        public void setGenDeadlineMinutes(long genDeadlineMinutes) { this.genDeadlineMinutes = genDeadlineMinutes; }
        public long getWallClockDeadlineMinutes() { return wallClockDeadlineMinutes; }
        public void setWallClockDeadlineMinutes(long wallClockDeadlineMinutes) { this.wallClockDeadlineMinutes = wallClockDeadlineMinutes; }
    }

    public String getTemplateDir() { return templateDir; }
    public void setTemplateDir(String templateDir) { this.templateDir = templateDir; }
    public String getWorkspaceDir() { return workspaceDir; }
    public void setWorkspaceDir(String workspaceDir) { this.workspaceDir = workspaceDir; }
    public String getArtifactsDir() { return artifactsDir; }
    public void setArtifactsDir(String artifactsDir) { this.artifactsDir = artifactsDir; }
    public String getPublicBaseUrl() { return publicBaseUrl; }
    public void setPublicBaseUrl(String publicBaseUrl) { this.publicBaseUrl = publicBaseUrl; }
    public Render getRender() { return render; }
    public Glm getGlm() { return glm; }
    public Tts getTts() { return tts; }
    public Qa getQa() { return qa; }
    public Retry getRetry() { return retry; }
}
