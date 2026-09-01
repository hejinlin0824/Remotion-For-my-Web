package com.wyf.factory.api;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 25 欢迎页静态契约（纯 classpath 断言，不起 Spring 上下文、零真实外呼）：
 * static/index.html 存在且非空；含关键端点与提交/轮询/播放锚点；单文件自包含——
 * 零外链资源引用（全文无任何 http(s) 引用，防 CDN 依赖混入，离线可用）。
 */
class WelcomePageTest {

    private static String page() throws IOException {
        try (InputStream in = WelcomePageTest.class.getResourceAsStream("/static/index.html")) {
            assertThat(in).as("classpath:/static/index.html 必须存在").isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("欢迎页存在且非空")
    void pageExistsAndNonEmpty() throws IOException {
        assertThat(page()).isNotBlank();
    }

    @Test
    @DisplayName("欢迎页含关键端点与提交/轮询/播放锚点")
    void containsKeyEndpointAndAnchors() throws IOException {
        String html = page();
        assertThat(html).contains("/api/v1/jobs");   // 提交/轮询/取消/播放/驳回清单共用前缀
        assertThat(html).contains("review-errors");  // 失败面板拉逐轮驳回清单
        assertThat(html).contains("<video");         // 播放区 video 元素
        assertThat(html).contains("imageBase64");    // IMAGE 输入裸 base64 字段（剥前缀后）
        assertThat(html).contains("location.hash");  // jobId 入 hash，刷新/发链接可恢复
        assertThat(html).contains("720p");           // 缺省渲染档位
    }

    @Test
    @DisplayName("欢迎页自包含：零外链资源引用")
    void selfContainedNoExternalReferences() throws IOException {
        String html = page();
        assertThat(html).doesNotContain("http://");
        assertThat(html).doesNotContain("https://");
    }
}
