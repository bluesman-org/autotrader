package nl.jimkaplan.autotrader.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Configuration for OpenAPI specification export.
 * This class exports the OpenAPI specification to files that can be imported into Postman.
 * The files are updated automatically when the application starts, ensuring they reflect
 * the latest API endpoints.
 */
@Configuration
public class OpenApiConfig implements ApplicationListener<WebServerInitializedEvent> {

    private static final Logger log = LoggerFactory.getLogger(OpenApiConfig.class);
    private static final String OPENAPI_JSON_FILENAME = "autotrader-openapi.json";
    private static final String OPENAPI_YAML_FILENAME = "autotrader-openapi.yaml";
    private static final String EXPORT_DIR = "src/main/resources/static/api-docs";

    @Override
    public void onApplicationEvent(WebServerInitializedEvent event) {
        log.info("Web server initialized. Exporting OpenAPI specification...");
        exportOpenApiSpecification();
    }

    @Value("${server.port:8080}")
    private int serverPort;

    @Value("${server.servlet.context-path:/}")
    private String contextPath;

    /**
     * Exports the OpenAPI specification to files.
     * This method is called both when the application starts and when the web server is initialized.
     */
    private void exportOpenApiSpecification() {
        try {
            // Create RestTemplate to call the OpenAPI endpoint
            RestTemplate restTemplate = new RestTemplate();

            // Build the URL to the OpenAPI endpoint
            String baseUrl = "http://localhost:" + serverPort;
            String apiDocsPath = contextPath.endsWith("/") ? contextPath + "v3/api-docs" : contextPath + "/v3/api-docs";
            String openApiUrl = baseUrl + apiDocsPath;

            log.info("Fetching OpenAPI specification from: {}", openApiUrl);

            // Get the OpenAPI specification
            ResponseEntity<String> response = restTemplate.getForEntity(openApiUrl, String.class);
            String jsonSpec = response.getBody();

            // Create export directory if it doesn't exist
            Path exportDirPath = Paths.get(EXPORT_DIR);
            if (!Files.exists(exportDirPath)) {
                Files.createDirectories(exportDirPath);
                log.info("Created directory for OpenAPI specification export: {}", exportDirPath);
            }

            // Export OpenAPI specification to JSON file
            Path jsonFilePath = exportDirPath.resolve(OPENAPI_JSON_FILENAME);
            Files.writeString(jsonFilePath, jsonSpec);
            log.info("Exported OpenAPI specification to JSON file: {}", jsonFilePath);

            // Export OpenAPI specification to YAML file
            // Convert JSON to YAML
            ObjectMapper jsonMapper = new ObjectMapper();
            Object jsonObject = jsonMapper.readValue(jsonSpec, Object.class);
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            String yamlSpec = yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(jsonObject);
            Path yamlFilePath = exportDirPath.resolve(OPENAPI_YAML_FILENAME);
            Files.writeString(yamlFilePath, yamlSpec);
            log.info("Exported OpenAPI specification to YAML file: {}", yamlFilePath);

            log.info("OpenAPI specification export completed successfully");
        } catch (Exception e) {
            log.error("Failed to export OpenAPI specification", e);
        }
    }

    /**
     * Creates a CommandLineRunner bean that exports the OpenAPI specification to files
     * when the application starts.
     *
     * @return A CommandLineRunner that exports the OpenAPI specification
     */
    @Bean
    public CommandLineRunner exportOpenApiSpec() {
        return args -> {
            try {
                // Wait a bit to ensure the server is fully started
                Thread.sleep(5000);
                exportOpenApiSpecification();
            } catch (Exception e) {
                log.error("Failed to export OpenAPI specification", e);
            }
        };
    }
}
