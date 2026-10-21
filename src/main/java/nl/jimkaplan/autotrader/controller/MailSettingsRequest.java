package nl.jimkaplan.autotrader.controller;

import lombok.Data;

/**
 * The SMTP settings the settings page and the API write. The password is write-only: a blank one
 * keeps the stored password.
 */
@Data
public class MailSettingsRequest {

    private String host;
    private Integer port;
    private String username;
    private String password;
    private String fromAddress;
    private boolean startTls;
}
