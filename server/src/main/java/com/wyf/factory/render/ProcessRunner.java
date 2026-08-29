package com.wyf.factory.render;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 子进程执行抽象（终审：npx/node/python 全部 cmd 包装，测试注入 fake 零真调）。
 * 命令元素各自独立（如 ["cmd","/c","npx","remotion","render",...]），不做 shell 字符串拼接。
 */
public interface ProcessRunner {

    /**
     * 在 cwd 下执行 command；extraEnv 合并进继承环境（实现方另行追加 NO_PROXY=*，
     * Global Constraint 7——Windows 注册表死代理坑）。超时强杀后返回 timedOut 标记的结果。
     *
     * @throws InterruptedException 等待期间被中断（进程已销毁）
     */
    ProcessResult run(Path cwd, List<String> command, Map<String, String> extraEnv, Duration timeout)
            throws InterruptedException;
}
