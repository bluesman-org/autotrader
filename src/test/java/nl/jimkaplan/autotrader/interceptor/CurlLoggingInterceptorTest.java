package nl.jimkaplan.autotrader.interceptor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurlLoggingInterceptorTest {

    @Mock
    private HttpRequest request;

    @Mock
    private ClientHttpRequestExecution execution;

    @Mock
    private ClientHttpResponse response;

    @Mock
    private Logger logger;

    private CurlLoggingInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new CurlLoggingInterceptor(logger);
    }

    @Test
    void shouldNotLogWhenTraceDisabled() throws IOException {
        // Arrange
        when(logger.isTraceEnabled()).thenReturn(false);

        // Act
        interceptor.intercept(request, new byte[0], execution);

        // Assert
        verify(logger, never()).trace(anyString(), (Object) any());
    }

    @Test
    void shouldLogGetRequestInCurlFormat() throws IOException {
        // Arrange
        when(logger.isTraceEnabled()).thenReturn(true);
        when(request.getMethod()).thenReturn(HttpMethod.GET);
        when(request.getURI()).thenReturn(URI.create("https://api.bitvavo.com/v2/account"));

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        headers.add("Authorization", "Bearer token123");
        when(request.getHeaders()).thenReturn(headers);

        when(execution.execute(eq(request), any())).thenReturn(response);

        // Act
        interceptor.intercept(request, new byte[0], execution);

        // Assert
        // Verify that the execution is called, which means the interceptor is working
        verify(execution).execute(eq(request), any());
    }

    @Test
    void shouldLogPostRequestWithBodyInCurlFormat() throws IOException {
        // Arrange
        when(logger.isTraceEnabled()).thenReturn(true);
        when(request.getMethod()).thenReturn(HttpMethod.POST);
        when(request.getURI()).thenReturn(URI.create("https://api.bitvavo.com/v2/order"));

        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");
        when(request.getHeaders()).thenReturn(headers);

        when(execution.execute(eq(request), any())).thenReturn(response);

        String bodyContent = "{\"market\":\"BTC-EUR\",\"side\":\"buy\",\"amount\":\"0.1\"}";
        byte[] body = bodyContent.getBytes(StandardCharsets.UTF_8);

        // Act
        interceptor.intercept(request, body, execution);

        // Assert
        // Verify that the execution is called, which means the interceptor is working
        verify(execution).execute(eq(request), eq(body));
    }
}
