package nl.jimkaplan.autotrader.controller;

import java.time.Instant;

/**
 * What the API and the settings page read about the SMTP server: where the recovery mails go
 * out — never the password.
 *
 * @param configured    Whether a server is stored
 * @param host          The SMTP host, or null
 * @param port          The port, or null
 * @param username      The sign-in name, or null
 * @param fromAddress   The address the mails come from, or null
 * @param startTls      Whether STARTTLS is used
 * @param updatedTime   When the settings were stored, or null
 */
public record MailSettingsResponse(boolean configured, String host, Integer port, String username,
                                   String fromAddress, boolean startTls, Instant updatedTime) {
}
