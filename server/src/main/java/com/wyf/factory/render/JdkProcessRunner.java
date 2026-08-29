package com.wyf.factory.render;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * ProcessRunner 生产实现：ProcessBuilder + 继承环境合并 extraEnv + 追加 NO_PROXY=*
 * （Global Constraint 7，Windows 注册表死代理坑）。stdout/stderr 由独立线程排空
 * （防管道塞死），UTF-8 解码。超时 destroyForcibly 后 Windows 补刀
 * taskkill /T /F /PID &lt;pid&gt;（cmd /c 包装进程树，杀整棵）。taskkill 缺失/失败
 * 尽力而为不掩盖主结果。
 */
@Component
public class JdkProcessRunner implements ProcessRunner {

    /** 补刀后等进程树退出/流排空的上限。 */
    private static final long KILL_WAIT_MILLIS = 15_000;

    @Override
    public ProcessResult run(Path cwd, List<String> command, Map<String, String> extraEnv, Duration timeout)
            throws InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(cwd.toFile());
        pb.environment().putAll(extraEnv);
        pb.environment().put("NO_PROXY", "*");

        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new UncheckedIOException("子进程启动失败：" + command, e);
        }
        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread outDrain = drain(process.getInputStream(), out);
        Thread errDrain = drain(process.getErrorStream(), err);

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            process.destroyForcibly();
            throw e;
        }
        if (!finished) {
            process.destroyForcibly();
            killTree(process.pid());
            process.waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
            awaitDrain(outDrain);
            awaitDrain(errDrain);
            return ProcessResult.timedOut(out.toString(), err.toString());
        }
        awaitDrain(outDrain);
        awaitDrain(errDrain);
        return new ProcessResult(process.exitValue(), out.toString(), err.toString(), false);
    }

    /** 流排空线程：读到 EOF 为止，UTF-8 聚合（node 管道输出恒 UTF-8）。 */
    private static Thread drain(InputStream stream, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            try (InputStream in = stream) {
                sink.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // 进程被强杀时流中断，尽力收集已有输出
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void awaitDrain(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(30));
    }

    /** Windows 进程树补刀：/T 连子进程 /F 强杀（cmd /c 包装的 npx→node 链）。 */
    private static void killTree(long pid) {
        try {
            new ProcessBuilder("taskkill", "/T", "/F", "/PID", String.valueOf(pid))
                    .start()
                    .waitFor(KILL_WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (IOException | InterruptedException ignored) {
            if (ignored instanceof InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            // 尽力而为：destroyForcibly 已杀主进程
        }
    }
}
