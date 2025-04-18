package nl.jimkaplan.autotrader.controller;

import org.junit.jupiter.api.Test;
import org.springframework.ui.Model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class HomeControllerTest {

    @Test
    void home_shouldAddAttributesToModelAndReturnWelcomeView() {
        // Arrange
        HomeController controller = new HomeController();
        Model model = mock(Model.class);

        // Act
        String viewName = controller.home(model);

        // Assert
        assertEquals("welcome", viewName);
        verify(model).addAttribute("appName", "Autotrader");
        verify(model).addAttribute("appDescription", "Automated trading with TradingView and Bitvavo");
    }
}