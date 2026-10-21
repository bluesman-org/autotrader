package nl.jimkaplan.autotrader.controller;

import lombok.Data;

import java.time.Instant;

/**
 * What the signup form submits for the installation's first account.
 */
@Data
public class SignupRequest {

    private String username;
    private String email;
    private String password;
    private String confirm;

    /**
     * What the pages call the reader; the username when left empty.
     */
    private String displayName;
}
