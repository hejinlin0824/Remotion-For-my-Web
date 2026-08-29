package com.wyf.factory.pipeline;

import com.wyf.factory.config.AppProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

/**
 * 外部资源并发闸（plan Task 10 / spec §4）：glm=2（LLM 工位：审题/素材/剧本/V4 judge）、
 * tts=1（DashScope 串行，与句间 interval 双保险）、render=2（remotion 渲染）、
 * qa=1（审帧三步子进程链）。数值来自 AppProperties（app.glm.concurrency / app.render.concurrency）。
 *
 * <p>各阶段内一次只持一个信号量（无嵌套获取），不存在交叉等待死锁；
 * 渲染/审帧阶段因协作者方法声明 InterruptedException，采用手动 acquire/release
 * （见 JobOrchestrator），其余阶段用 {@link #withResource} lambda 形态。</p>
 */
@Component
public class ResourceSemaphores {

    private final Semaphore glm;
    private final Semaphore tts;
    private final Semaphore render;
    private final Semaphore qa;

    public ResourceSemaphores(AppProperties props) {
        this.glm = new Semaphore(props.getGlm().getConcurrency());
        this.tts = new Semaphore(1);
        this.render = new Semaphore(props.getRender().getConcurrency());
        this.qa = new Semaphore(1);
    }

    public Semaphore glm() { return glm; }

    public Semaphore tts() { return tts; }

    public Semaphore render() { return render; }

    public Semaphore qa() { return qa; }

    /** glm 闸内的 lambda 执行（阻塞等待许可，不响应中断——worker 中断语义在阶段边界处理）。 */
    public <T> T withGlm(Supplier<T> fn) {
        return withResource(glm, fn);
    }

    public <T> T withTts(Supplier<T> fn) {
        return withResource(tts, fn);
    }

    /**
     * 信号量闸内执行：acquire 阻塞等待，finally 恒 release。
     * 中断等待以 IllegalStateException 上抛（属于调度层异常，非任务失败）。
     */
    public static <T> T withResource(Semaphore semaphore, Supplier<T> fn) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待资源信号量被中断", e);
        }
        try {
            return fn.get();
        } finally {
            semaphore.release();
        }
    }
}
