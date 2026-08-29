package com.wyf.factory;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归守卫（T11b）：全上下文必须能启动，进默认套件（不标 {@code @Tag("slow")}）。
 *
 * <p>背景：{@code Secrets / GlmClient / DashScopeTts / TtsPipeline} 四个 @Component 曾各有
 * 「生产构造器 + 测试构造器」两个 public 构造器且零 {@code @Autowired}，多构造器无标注时
 * Spring 找默认构造器（不存在）→ 全上下文必然崩（{@code No default constructor found}，
 * 实测首崩 glmClient）。此前 201 个切片测试全是 @WebMvcTest/@DataJpaTest/手工注入 fake，
 * 不加载全上下文，掩盖至 golden 冒烟（T11）才暴露。本测试保证任何 bean 装配歧义
 * 在 {@code mvn test} 阶段就爆出，不再依赖 slow IT 兜底。</p>
 *
 * <p>key 行为（已查证）：四个类全部懒读取——{@code Secrets.glmKey()/ttsKey()} 只在
 * GLM/TTS 请求方法内调用，构造期不碰 key；编排器 {@code @PostConstruct} 只建线程池，
 * {@code @Scheduled} 领单只查空库。key 缺失不影响启动，故无需伪造 key 属性。</p>
 *
 * <p>隔离：H2 覆盖为内存库（application.yml 默认指向 server/data 文件库，留给生产运行，
 * 单测套件不落盘、不沾运行中服务的库）。</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.datasource.url=jdbc:h2:mem:contextloads;DB_CLOSE_DELAY=-1")
@DisplayName("全上下文启动守卫：bean 装配歧义必须在默认套件爆出")
class ContextLoadsTest {

    @Test
    @DisplayName("上下文能启动（生产 bean 全部装配成功）")
    void contextStarts(@Autowired ApplicationContext context) {
        assertThat(context).isNotNull();
    }
}
