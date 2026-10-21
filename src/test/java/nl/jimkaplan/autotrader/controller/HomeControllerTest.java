package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.service.AppUserService;
import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HomeControllerTest {

    private final AppUserService users = mock(AppUserService.class);
    private final HomeController controller = new HomeController(users);

    @Test
    void home_shouldAddAttributesToModelAndReturnWelcomeView() {
        // Arrange
        when(users.exists()).thenReturn(true);
        Model model = mock(Model.class);

        // Act
        String viewName = controller.home(model);

        // Assert
        assertEquals("welcome", viewName);
        verify(model).addAttribute("appName", "Autotrader");
        verify(model).addAttribute("appDescription", "Automated trading on Bitvavo with an in-app strategy engine");
    }

    @Test
    void home_sendsAFreshInstallationToTheSignupPage() {
        // No account exists yet, so there is nothing to see before one is created.
        when(users.exists()).thenReturn(false);
        Model model = mock(Model.class);

        assertEquals("redirect:/signup", controller.home(model));
    }
}
