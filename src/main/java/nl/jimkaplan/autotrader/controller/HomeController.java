package nl.jimkaplan.autotrader.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller for handling requests to the home/welcome page.
 */
@Controller
public class HomeController {

    /**
     * Handles requests to the root path and serves the welcome page.
     *
     * @param model the model to add attributes to
     * @return the name of the view to render
     */
    @GetMapping("/")
    public String home(Model model) {
        model.addAttribute("appName", "Autotrader");
        model.addAttribute("appDescription", "Automated trading with TradingView and Bitvavo");
        return "welcome";
    }
}