package nl.jimkaplan.autotrader.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The operator guide. It is a single static page, so it needs no model and no repository; the
 * content lives in {@code templates/help/index.html}.
 */
@Controller
public class HelpController {

    /**
     * @return The help page
     */
    @GetMapping("/ui/help")
    public String help() {
        return "help/index";
    }
}
