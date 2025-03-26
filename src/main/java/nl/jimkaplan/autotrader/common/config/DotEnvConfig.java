package nl.jimkaplan.autotrader.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;

@Configuration
public class DotEnvConfig {
    private Resource getEnvResource(String path) {
        // Check classpath first
        Resource classpathResource = new ClassPathResource(path);
        if (classpathResource.exists()) {
            return classpathResource;
        }

        // Check project root
        Resource fileResource = new FileSystemResource(path);
        if (fileResource.exists()) {
            return fileResource;
        }

        throw new IllegalStateException("No .env file found in classpath or project root");
    }

    @Bean
    @Profile("!test")
    public PropertySourcesPlaceholderConfigurer devPropertyConfig() {
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setLocation(getEnvResource(".env"));
        return configurer;
    }

    @Bean
    @Profile("test")
    public PropertySourcesPlaceholderConfigurer testPropertyConfig() {
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setLocation(getEnvResource(".env.test"));
        return configurer;
    }
}