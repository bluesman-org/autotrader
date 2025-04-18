package nl.jimkaplan.autotrader.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC configuration for the application.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * Configure view controllers to map paths to view names without the need for explicit controllers.
     * This is useful for simple cases where no additional logic is needed.
     *
     * @param registry the registry to add view controllers to
     */
    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Map the empty path to forward to the root path
        // This ensures that /autotrader/ forwards to /autotrader which is handled by HomeController
        registry.addViewController("").setViewName("forward:/");

        // Add additional view controllers if needed
    }
}
