package nl.jimkaplan.autotrader.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.ErrorResponse;
import nl.jimkaplan.autotrader.service.MailSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SMTP server the recovery mails go out through: what is configured (never the password) and
 * how to replace it. The password is write-only.
 */
@Slf4j
@RestController
@RequestMapping("/api/mail-settings")
@Tag(name = "Mail settings", description = "The SMTP server the recovery mails go out through")
public class MailSettingsController {

    private final MailSettingsService mailSettingsService;

    public MailSettingsController(MailSettingsService mailSettingsService) {
        this.mailSettingsService = mailSettingsService;
    }

    /**
     * Read what is configured.
     *
     * @return The masked view
     */
    @Operation(summary = "Read the SMTP settings (no password)",
            description = "Returns the host, port, sign-in name, from-address and STARTTLS flag; the "
                    + "password is never returned.")
    @GetMapping
    public MailSettingsResponse current() {
        return mailSettingsService.view();
    }

    /**
     * Store or replace the SMTP server.
     *
     * @param request The settings; a blank password keeps the stored one
     * @return The masked view of what is stored now
     */
    @Operation(summary = "Store or replace the SMTP settings",
            description = "Stores the password encrypted. A blank password keeps the stored one, so "
                    + "the host can be corrected without re-typing it.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "The settings were stored"),
            @ApiResponse(responseCode = "400", description = "The host, from-address or first "
                    + "password was missing",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping
    public MailSettingsResponse store(@RequestBody MailSettingsRequest request) {
        mailSettingsService.save(request.getHost(), request.getPort(), request.getUsername(),
                request.getPassword(), request.getFromAddress(), request.isStartTls());
        return mailSettingsService.view();
    }
}
