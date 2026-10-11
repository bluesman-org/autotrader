package nl.jimkaplan.autotrader.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebConfigTest {

    @Test
    void addViewControllers_forwardsTheEmptyPathToTheRoot() {
        ViewControllerRegistry registry = mock(ViewControllerRegistry.class);
        ViewControllerRegistration registration = mock(ViewControllerRegistration.class);
        when(registry.addViewController("")).thenReturn(registration);

        new WebConfig().addViewControllers(registry);

        verify(registry).addViewController("");
        verify(registration).setViewName("forward:/");
    }
}
