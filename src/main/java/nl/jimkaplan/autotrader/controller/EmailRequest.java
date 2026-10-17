package nl.jimkaplan.autotrader.controller;

/**
 * The address a recovery mail is asked for.
 *
 * @param email The address
 */
public record EmailRequest(String email) {
}
