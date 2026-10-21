package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.service.AppUserService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller for handling requests to the home/welcome page.
 */
@Controller
public class HomeController {

    private final AppUserService users;

    public HomeController(AppUserService users) {
        this.users = users;
    }

    /**
     * Handles requests to the root path and serves the welcome page; a fresh installation with no
     * account yet goes to the signup page instead, because nothing can be seen before it exists.
     *
     * @param model the model to add attributes to
     * @return the name of the view to render, or a redirect to the signup page
     */
    @GetMapping("/")
    public String home(Model model) {
        if (!users.exists()) {
            return "redirect:/signup";
        }
        model.addAttribute("appName", "Autotrader");
        model.addAttribute("appDescription", "Automated trading on Bitvavo with an in-app strategy engine");
        return "welcome";
    }
}