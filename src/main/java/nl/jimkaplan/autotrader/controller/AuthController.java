package nl.jimkaplan.autotrader.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.service.AppUserService;
import nl.jimkaplan.autotrader.service.PasswordResetService;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The way in: the sign-in page, the first account's signup and the password-recovery pages.
 *
 * <p>The signup page is open only while the installation has no account; afterwards it reports
 * that signups are closed. A recovery request always answers the same way, whether or not the
 * address belongs to the account.</p>
 */
@Slf4j
@Controller
public class AuthController {

    private final AppUserService users;
    private final PasswordResetService recovery;
    private final AuthenticationManager authenticationManager;

    public AuthController(AppUserService users, PasswordResetService recovery,
                          AuthenticationManager authenticationManager) {
        this.users = users;
        this.recovery = recovery;
        this.authenticationManager = authenticationManager;
    }

    /**
     * @return The sign-in page, or the signup page on a fresh installation with no account yet
     */
    @GetMapping("/login")
    public String login() {
        return users.exists() ? "login" : "redirect:/signup";
    }

    /**
     * @param model The view model
     * @return The signup form, or the closed notice once the account exists
     */
    @GetMapping("/signup")
    public String signup(Model model) {
        if (users.exists()) {
            model.addAttribute("closed", true);
        } else if (!model.containsAttribute("form")) {
            model.addAttribute("form", new SignupRequest());
        }
        return "signup";
    }

    /**
     * Create the installation's first account and sign it in.
     *
     * @param form    The submitted account
     * @param model   The view model, for the error banner when a rule is broken
     * @param request The request, so the new session holds the sign-in
     * @return A redirect to the live page, or the form again with the reason
     */
    @PostMapping("/signup")
    public String create(@ModelAttribute("form") SignupRequest form, Model model,
                         HttpServletRequest request) {
        if (users.exists()) {
            model.addAttribute("closed", true);
            return "signup";
        }
        try {
            AppUser user = users.createFirst(form.getUsername(), form.getEmail(), form.getPassword(),
                    form.getConfirm(), form.getDisplayName());
            signIn(request, user.getUsername(), form.getPassword());
            return "redirect:/ui/live";
        } catch (IllegalArgumentException e) {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("form", form);
            // Never send a password back into the page.
            form.setPassword(null);
            form.setConfirm(null);
            return "signup";
        }
    }

    /**
     * @return The recovery request form
     */
    @GetMapping("/forgot-password")
    public String forgotPassword() {
        return "forgot-password";
    }

    /**
     * @param email The submitted address
     * @param model The view model
     * @return The recovery request form with the one answer it ever gives
     */
    @PostMapping("/forgot-password")
    public String requestRecovery(@RequestParam(required = false) String email, Model model) {
        model.addAttribute("message", recovery.request(email));
        model.addAttribute("email", email);
        return "forgot-password";
    }

    /**
     * @param token The token from the recovery link
     * @param model The view model
     * @return The new-password form, or its notice when the link is no longer valid
     */
    @GetMapping("/reset-password")
    public String resetPassword(@RequestParam(required = false) String token, Model model) {
        model.addAttribute("token", token);
        model.addAttribute("valid", recovery.valid(token));
        return "reset-password";
    }

    /**
     * Set the new password from a recovery link.
     *
     * @param token    The token from the link
     * @param password The new password
     * @param confirm  The new password again
     * @param model    The view model
     * @return A redirect to the sign-in page, or the form again with the reason
     */
    @PostMapping("/reset-password")
    public String applyReset(@RequestParam(required = false) String token,
                             @RequestParam(required = false) String password,
                             @RequestParam(required = false) String confirm, Model model) {
        try {
            recovery.reset(token, password, confirm);
            return "redirect:/login?reset";
        } catch (IllegalArgumentException e) {
            model.addAttribute("token", token);
            model.addAttribute("valid", recovery.valid(token));
            model.addAttribute("error", e.getMessage());
            return "reset-password";
        }
    }

    /**
     * Sign the freshly created account in on the session it signed up with, so the first thing it
     * sees is the live page rather than the sign-in form.
     */
    private void signIn(HttpServletRequest request, String username, String password) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(username, password));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        request.getSession(true).setAttribute(
                HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
    }
}
