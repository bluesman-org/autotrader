package nl.jimkaplan.autotrader.controller;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.strategy.service.DisplayZone;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The settings page: the system-wide Bitvavo API key the engine trades with and the ledger reads
 * the account with. The key is shown as a hint only — it is write-only — and replacing it stores it
 * encrypted.
 */
@Slf4j
@Controller
@RequestMapping("/ui/settings")
public class SettingsUiController {

    private final BitvavoCredentialsService credentialsService;
    private final DisplayZone display;

    public SettingsUiController(BitvavoCredentialsService credentialsService,
                                @Value("${autotrader.ui.time-zone:}") String timeZone) {
        this.credentialsService = credentialsService;
        this.display = new DisplayZone(timeZone);
    }

    /**
     * @param model The view model
     * @return The settings page
     */
    @GetMapping
    public String page(Model model) {
        model.addAttribute("credentials", credentialsService.view());
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new BitvavoCredentialsRequest());
        }
        model.addAttribute("zoneLabel", display.label());
        model.addAttribute("updatedText", updatedText());
        return "settings/index";
    }

    /**
     * Store or replace the key.
     *
     * @param form               The submitted key, secret and operator id
     * @param redirectAttributes Carries the outcome back to the page; the form itself is never
     *                           flashed back because it holds the secret
     * @return A redirect to the page
     */
    @PostMapping
    public String save(@ModelAttribute("form") BitvavoCredentialsRequest form,
                       RedirectAttributes redirectAttributes) {
        try {
            credentialsService.save(form.getApiKey(), form.getApiSecret(), form.getOperatorId());
            log.info("The settings page replaced the Bitvavo API key");
            redirectAttributes.addFlashAttribute("message",
                    "The Bitvavo API key was stored; the engine and the ledger use it from now on.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/ui/settings";
    }

    private String updatedText() {
        return credentialsService.get()
                .map(credentials -> credentials.getUpdatedTime() == null ? null
                        : display.minute(credentials.getUpdatedTime()) + " (" + display.label() + ")")
                .orElse(null);
    }
}
