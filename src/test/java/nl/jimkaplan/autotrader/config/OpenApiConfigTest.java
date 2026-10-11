package nl.jimkaplan.autotrader.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class OpenApiConfigTest {

    private static final String SPEC_JSON =
            "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"autotrader\",\"version\":\"v1\"}}";

    @TempDir
    Path exportDir;

    private HttpServer server;
    private int serverPort;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = SPEC_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        serverPort = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void onApplicationEvent_exportsTheSpecificationAsJsonAndYaml() throws IOException {
        OpenApiConfig config = configWith("/autotrader");

        config.onApplicationEvent(mock(WebServerInitializedEvent.class));

        Path json = exportDir.resolve("autotrader-openapi.json");
        Path yaml = exportDir.resolve("autotrader-openapi.yaml");
        assertTrue(Files.exists(json));
        assertEquals(SPEC_JSON, Files.readString(json));
        assertTrue(Files.exists(yaml));
        assertTrue(Files.readString(yaml).contains("openapi"));
    }

    @Test
    void exportOpenApiSpec_exportsAfterTheConfiguredDelay() throws Exception {
        OpenApiConfig config = configWith("/autotrader/");
        ReflectionTestUtils.setField(config, "exportStartupDelayMs", 0L);

        config.exportOpenApiSpec().run();

        assertTrue(Files.exists(exportDir.resolve("autotrader-openapi.json")));
    }

    @Test
    void onApplicationEvent_whenTheServerIsUnreachable_logsAndContinues() throws IOException {
        int unusedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            unusedPort = socket.getLocalPort();
        }
        OpenApiConfig config = configWith("/autotrader");
        ReflectionTestUtils.setField(config, "serverPort", unusedPort);

        assertDoesNotThrow(() -> config.onApplicationEvent(mock(WebServerInitializedEvent.class)));
        assertFalse(Files.exists(exportDir.resolve("autotrader-openapi.json")));
    }

    private OpenApiConfig configWith(String contextPath) {
        OpenApiConfig config = new OpenApiConfig();
        ReflectionTestUtils.setField(config, "serverPort", serverPort);
        ReflectionTestUtils.setField(config, "contextPath", contextPath);
        ReflectionTestUtils.setField(config, "exportDir", exportDir.toString());
        return config;
    }
}
