package com.wyf.factory.glm;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * HTTP 传输抽象（函数接口）：JDK HttpClient 无法 mock，抽出一层供 GlmClient 单测注入 fake。
 * 生产实现 {@link JdkHttpTransport}。
 */
@FunctionalInterface
public interface HttpTransport {

    HttpResponse<byte[]> send(HttpRequest request, byte[] body) throws IOException, InterruptedException;
}
