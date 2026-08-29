package com.wyf.factory.render;

/**
 * 子进程一次执行的结果。timedOut=true 表示超时强杀（含 Windows taskkill /T /F 补刀），
 * 此时 exitCode 无业务含义（哨兵 -1）。
 */
public record ProcessResult(int exitCode, String stdout, String stderr, boolean timedOut) {

    /** 超时强杀结果的哨兵 exit code。 */
    public static final int TIMEOUT_EXIT = -1;

    public static ProcessResult timedOut(String stdout, String stderr) {
        return new ProcessResult(TIMEOUT_EXIT, stdout, stderr, true);
    }
}
