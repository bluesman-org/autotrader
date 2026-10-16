package nl.jimkaplan.autotrader.service;

import lombok.extern.slf4j.Slf4j;
import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.repository.AppUserRepository;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The application's account: signing in, the first-run signup and the password rules.
 *
 * <p>The installation holds one account, so the signup page creates it only while none exists;
 * afterwards the form reports that signups are closed. Usernames and addresses are stored
 * lowercased, and passwords are BCrypt hashes — only the moment of a sign-in or a change sees the
 * plaintext.</p>
 */
@Slf4j
@Service
public class AppUserService implements UserDetailsService {

    /**
     * The shortest password the forms accept; the reasoning is in the docs — length beats
     * composition rules.
     */
    static final int MIN_PASSWORD_LENGTH = 10;

    /**
     * Usernames are what the login form and the nav show: 3–40 characters, plain and lowercased.
     */
    static final int MIN_USERNAME_LENGTH = 3;
    static final int MAX_USERNAME_LENGTH = 40;

    private static final Pattern USERNAME_SHAPE = Pattern.compile("[a-z0-9][a-z0-9._-]*");
    private static final Pattern EMAIL_SHAPE = Pattern.compile("[^@\\s]+@[^@\\s]+\\.[^@\\s]+");

    private final AppUserRepository repository;
    private final PasswordEncoder passwordEncoder;

    public AppUserService(AppUserRepository repository, PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * @param username The submitted login name
     * @return The account as Spring Security needs it
     * @throws UsernameNotFoundException when no account carries that name
     */
    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser user = repository.findByUsername(normalize(username))
                .orElseThrow(() -> new UsernameNotFoundException("No account named " + username));
        List<String> roles = user.getRoles() == null || user.getRoles().isEmpty()
                ? List.of(AppUser.ROLE_USER) : user.getRoles();
        return User.withUsername(user.getUsername())
                .password(user.getPasswordHash())
                .roles(roles.toArray(String[]::new))
                .disabled(!user.isEnabled())
                .build();
    }

    /**
     * @return Whether any account exists; the signup page is open only while none does
     */
    public boolean exists() {
        return repository.count() > 0;
    }

    /**
     * @param username The login name, lowercased
     * @return The account with that name
     */
    public Optional<AppUser> find(String username) {
        return repository.findByUsername(normalize(username));
    }

    /**
     * Create the installation's first account. The only way an account comes into being: once one
     * exists this refuses, so a second cannot be added from the page.
     *
     * @param username    The login name
     * @param email       The recovery address
     * @param password    The password
     * @param confirm     The password again
     * @param displayName What the pages call the reader; the username when empty
     * @return The stored account
     * @throws IllegalArgumentException when a rule is broken or an account already exists
     */
    public AppUser createFirst(String username, String email, String password, String confirm,
                               String displayName) {
        if (exists()) {
            throw new IllegalArgumentException(
                    "Signups are closed: this installation already has an account.");
        }
        String name = username(username);
        String address = email(email);
        validatePassword(password, confirm);
        String shown = displayName == null || displayName.isBlank() ? name : displayName.trim();
        AppUser user = AppUser.builder()
                .username(name)
                .email(address)
                .passwordHash(passwordEncoder.encode(password))
                .displayName(shown)
                .roles(List.of(AppUser.ROLE_USER))
                .enabled(true)
                .createdTime(Instant.now())
                .build();
        AppUser stored = repository.save(user);
        log.info("Created the installation's account {}", name);
        return stored;
    }

    /**
     * Change an account's password, proving the current one first.
     *
     * @param username The login name
     * @param current  The password in use now
     * @param password The new password
     * @param confirm  The new password again
     * @throws IllegalArgumentException when the current password is wrong or the new one breaks a
     *                                  rule
     */
    public void changePassword(String username, String current, String password, String confirm) {
        AppUser user = repository.findByUsername(normalize(username))
                .orElseThrow(() -> new IllegalArgumentException("No account named " + username));
        if (current == null || !passwordEncoder.matches(current, user.getPasswordHash())) {
            throw new IllegalArgumentException("The current password is not correct.");
        }
        setPassword(user, password, confirm);
    }

    /**
     * Store a new password for an account, without asking for the old one — for the recovery flow,
     * where the token has already proved who is asking.
     *
     * @param user     The account
     * @param password The new password
     * @param confirm  The new password again
     * @throws IllegalArgumentException when the password breaks a rule
     */
    public void setPassword(AppUser user, String password, String confirm) {
        validatePassword(password, confirm);
        user.setPasswordHash(passwordEncoder.encode(password));
        repository.save(user);
        log.info("Stored a new password for account {}", user.getUsername());
    }

    /**
     * Note a successful sign-in on the account.
     *
     * @param username The login name
     */
    public void recordLogin(String username) {
        repository.findByUsername(normalize(username)).ifPresent(user -> {
            user.setLastLoginTime(Instant.now());
            repository.save(user);
        });
    }

    private String username(String username) {
        String name = normalize(username);
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("The username is required.");
        }
        if (name.length() < MIN_USERNAME_LENGTH || name.length() > MAX_USERNAME_LENGTH) {
            throw new IllegalArgumentException("The username must be between "
                    + MIN_USERNAME_LENGTH + " and " + MAX_USERNAME_LENGTH + " characters.");
        }
        if (!USERNAME_SHAPE.matcher(name).matches()) {
            throw new IllegalArgumentException("The username can use letters, digits, dots, "
                    + "underscores and hyphens, and starts with a letter or a digit.");
        }
        return name;
    }

    private String email(String email) {
        String address = normalize(email);
        if (address == null || address.isEmpty()) {
            throw new IllegalArgumentException("The email address is required; it is how a "
                    + "forgotten password is recovered.");
        }
        if (!EMAIL_SHAPE.matcher(address).matches()) {
            throw new IllegalArgumentException("That does not look like an email address.");
        }
        return address;
    }

    private void validatePassword(String password, String confirm) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("The password must be at least "
                    + MIN_PASSWORD_LENGTH + " characters.");
        }
        if (!password.equals(confirm)) {
            throw new IllegalArgumentException("The two passwords are not the same.");
        }
    }

    private static String normalize(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
