package nl.jimkaplan.autotrader.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import nl.jimkaplan.autotrader.service.PasswordResetService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Password recovery over the API, for a script rather than a browser. The answer is the same
 * sentence the page shows, whenever the address exists or not.
 */
@RestController
@RequestMapping("/api/password-recovery")
@Tag(name = "Password recovery", description = "Ask for a password-recovery mail")
public class AuthApiController {

    private final PasswordResetService recovery;

    public AuthApiController(PasswordResetService recovery) {
        this.recovery = recovery;
    }

    /**
     * Ask for a recovery mail.
     *
     * @param request The address to send to, when it belongs to the account
     * @return The one answer a recovery request ever gives
     */
    @Operation(summary = "Ask for a password-recovery mail",
            description = "Always answers the same way, whether or not the address belongs to the "
                    + "account; when it does, a single-use link is mailed to it.")
    @PostMapping
    public MessageResponse request(@RequestBody EmailRequest request) {
        return new MessageResponse(recovery.request(request == null ? null : request.email()));
    }
}
