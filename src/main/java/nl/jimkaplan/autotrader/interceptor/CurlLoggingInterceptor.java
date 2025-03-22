package nl.jimkaplan.autotrader.interceptor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Interceptor that logs HTTP requests in cURL format.
 */
@Component
public class CurlLoggingInterceptor implements ClientHttpRequestInterceptor {

    private final Logger log;

    public CurlLoggingInterceptor() {
        this(LoggerFactory.getLogger(CurlLoggingInterceptor.class));
    }

    // Constructor for testing
    CurlLoggingInterceptor(Logger logger) {
        this.log = logger;
    }

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        logRequest(request, body);
        return execution.execute(request, body);
    }

    private void logRequest(HttpRequest request, byte[] body) {
        if (!log.isTraceEnabled()) {
            return;
        }

        StringBuilder curlCommand = new StringBuilder("curl -X ");
        curlCommand.append(request.getMethod());

        // Add URL
        curlCommand.append(" '").append(request.getURI()).append("'");

        // Add headers
        HttpHeaders headers = request.getHeaders();
        for (Map.Entry<String, List<String>> header : headers.entrySet()) {
            for (String value : header.getValue()) {
                String key = header.getKey();
                if (key.contains("Key") || key.contains("Secret")) {
                    value = "REDACTED";
                }
                curlCommand.append(" -H '").append(key).append(": ").append(value).append("'");
            }
        }

        // Add request body if present
        if (body != null && body.length > 0) {
            String bodyString = new String(body, StandardCharsets.UTF_8);
            // Escape single quotes in the body
            bodyString = bodyString.replace("'", "'\\''");
            curlCommand.append(" -d '").append(bodyString).append("'");
        }

        log.trace("Request (curl): {}", curlCommand);
    }
}
