package nl.jimkaplan.autotrader.controller;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.service.AppUserService;
import nl.jimkaplan.autotrader.service.BitvavoCredentialsService;
import nl.jimkaplan.autotrader.service.MailSettingsService;
import nl.jimkaplan.autotrader.strategy.service.DisplayZone;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * The settings page: the system-wide Bitvavo API key the engine trades with and the ledger reads
 * the account with, the SMTP server the recovery mails go out through, and the account's password.
 *
 * <p>Every secret is shown as a state, never as itself — the key, the SMTP password and the account
 * password are all write-only.</p>
 */
@Slf4j
@Controller
@RequestMapping("/ui/settings")
public class SettingsUiController {

    private final BitvavoCredentialsService credentialsService;
    private final MailSettingsService mailSettingsService;
    private final AppUserService users;
    private final DisplayZone display;

    public SettingsUiController(BitvavoCredentialsService credentialsService,
                                MailSettingsService mailSettingsService, AppUserService users,
                                @Value("${autotrader.ui.time-zone:}") String timeZone) {
        this.credentialsService = credentialsService;
        this.mailSettingsService = mailSettingsService;
        this.users = users;
        this.display = new DisplayZone(timeZone);
    }

    /**
     * @param model          The view model
     * @param authentication The signed-in account
     * @return The settings page
     */
    @GetMapping
    public String page(Model model, Authentication authentication) {
        BitvavoCredentialsResponse view = credentialsService.view();
        model.addAttribute("credentials", view);
        if (!model.containsAttribute("form")) {
            model.addAttribute("form", new BitvavoCredentialsRequest());
        }
        MailSettingsResponse mail = mailSettingsService.view();
        model.addAttribute("mail", mail);
        if (!model.containsAttribute("mailForm")) {
            model.addAttribute("mailForm", mailForm(mail));
        }
        AppUser account = authentication == null ? null
                : users.find(authentication.getName()).orElse(null);
        model.addAttribute("account", account);
        model.addAttribute("lastLoginText", account == null || account.getLastLoginTime() == null
                ? null : display.minute(account.getLastLoginTime()) + " (" + display.label() + ")");
        model.addAttribute("zoneLabel", display.label());
        model.addAttribute("updatedText", updatedText(view));
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

    /**
     * Store or replace the SMTP server the recovery mails go out through.
     *
     * @param form               The submitted settings
     * @param redirectAttributes Carries the outcome back to the page
     * @return A redirect to the page
     */
    @PostMapping("/mail")
    public String saveMail(@ModelAttribute("mailForm") MailSettingsRequest form,
                           RedirectAttributes redirectAttributes) {
        try {
            mailSettingsService.save(form.getHost(), form.getPort(), form.getUsername(),
                    form.getPassword(), form.getFromAddress(), form.isStartTls());
            log.info("The settings page stored the SMTP settings");
            redirectAttributes.addFlashAttribute("message",
                    "The SMTP settings were stored; recovery mails go out through them from now on.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/ui/settings";
    }

    /**
     * Change the signed-in account's password.
     *
     * @param current            The password in use now
     * @param password           The new password
     * @param confirm            The new password again
     * @param authentication     The signed-in account
     * @param redirectAttributes Carries the outcome back to the page
     * @return A redirect to the page
     */
    @PostMapping("/password")
    public String changePassword(@RequestParam(required = false) String current,
                                 @RequestParam(required = false) String password,
                                 @RequestParam(required = false) String confirm,
                                 Authentication authentication,
                                 RedirectAttributes redirectAttributes) {
        try {
            users.changePassword(authentication.getName(), current, password, confirm);
            log.info("The account {} changed its password", authentication.getName());
            redirectAttributes.addFlashAttribute("message", "Your password was changed.");
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/ui/settings";
    }

    /**
     * @param view The stored SMTP settings
     * @return The form's starting point: what is stored, or STARTTLS on a fresh installation
     */
    private MailSettingsRequest mailForm(MailSettingsResponse view) {
        MailSettingsRequest form = new MailSettingsRequest();
        form.setStartTls(true);
        if (view.configured()) {
            form.setHost(view.host());
            form.setPort(view.port());
            form.setUsername(view.username());
            form.setFromAddress(view.fromAddress());
            form.setStartTls(view.startTls());
        }
        return form;
    }

    private String updatedText(BitvavoCredentialsResponse view) {
        return view.updatedTime() == null ? null
                : display.minute(view.updatedTime()) + " (" + display.label() + ")";
    }
}
