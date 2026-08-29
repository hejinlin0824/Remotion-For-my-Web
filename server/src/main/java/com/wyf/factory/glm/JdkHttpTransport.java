package com.wyf.factory.glm;

import com.wyf.factory.config.AppProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 生产传输：JDK HttpClient（不引额外 HTTP 库）。
 * 连接超时 10s；请求超时 = app.glm.timeout-seconds。
 */
@Component
public class JdkHttpTransport implements HttpTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkHttpTransport(AppProperties props) {
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.requestTimeout = Duration.ofSeconds(props.getGlm().getTimeoutSeconds());
    }

    @Override
    public HttpResponse<byte[]> send(HttpRequest request, byte[] body) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(request.uri())
                .timeout(requestTimeout)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        request.headers().map().forEach((name, values) -> values.forEach(value -> builder.header(name, value)));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}
