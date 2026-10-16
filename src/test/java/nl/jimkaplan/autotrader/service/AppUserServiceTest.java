package nl.jimkaplan.autotrader.service;

import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The account rules: the first signup only, the input rules with their reasons, and the password
 * paths — never a plaintext hash.
 */
class AppUserServiceTest {

    private final AppUserRepository repository = mock(AppUserRepository.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();
    private final AppUserService service = new AppUserService(repository, encoder);

    @Test
    void createFirstStoresTheAccountLowercasedAndHashed() {
        when(repository.count()).thenReturn(0L);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AppUser user = service.createFirst("Jim", " Jim@Example.COM ", "long-password",
                "long-password", null);

        assertEquals("jim", user.getUsername());
        assertEquals("jim@example.com", user.getEmail());
        assertEquals("jim", user.getDisplayName());
        assertEquals(List.of(AppUser.ROLE_USER), user.getRoles());
        assertTrue(user.isEnabled());
        assertNotNull(user.getCreatedTime());
        assertNotEquals("long-password", user.getPasswordHash());
        assertTrue(encoder.matches("long-password", user.getPasswordHash()));
    }

    @Test
    void createFirstRefusesASecondAccount() {
        when(repository.count()).thenReturn(1L);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.createFirst("jim", "jim@example.com", "long-password",
                        "long-password", null));

        assertTrue(error.getMessage().contains("Signups are closed"));
        verify(repository, never()).save(any());
    }

    @Test
    void everyRuleSpeaksUp() {
        when(repository.count()).thenReturn(0L);

        assertMessage(() -> service.createFirst("ji", "jim@example.com", "long-password",
                "long-password", null), "between 3 and 40");
        assertMessage(() -> service.createFirst("Jim!", "jim@example.com", "long-password",
                "long-password", null), "letters, digits");
        assertMessage(() -> service.createFirst("jim", "not-an-email", "long-password",
                "long-password", null), "does not look like an email");
        assertMessage(() -> service.createFirst("jim", "jim@example.com", "short", "short",
                null), "at least 10 characters");
        assertMessage(() -> service.createFirst("jim", "jim@example.com", "long-password",
                "different", null), "not the same");
    }

    @Test
    void theAccountReadsAsSpringSecurityNeedsIt() {
        AppUser user = AppUser.builder().username("jim").passwordHash("hash").enabled(true).build();
        when(repository.findByUsername("jim")).thenReturn(Optional.of(user));

        UserDetails details = service.loadUserByUsername(" Jim ");

        assertEquals("jim", details.getUsername());
        assertEquals("hash", details.getPassword());
        assertTrue(details.isEnabled());
        assertTrue(details.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("ROLE_USER")));
    }

    @Test
    void anUnknownNameIsNoAccount() {
        when(repository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("nobody"));
    }

    @Test
    void adminDisablingIsHonoured() {
        AppUser user = AppUser.builder().username("jim").passwordHash("hash").enabled(false)
                .roles(List.of("USER")).build();
        when(repository.findByUsername("jim")).thenReturn(Optional.of(user));

        assertFalse(service.loadUserByUsername("jim").isEnabled());
    }

    @Test
    void changePasswordProvesTheCurrentOneFirst() {
        AppUser user = AppUser.builder().username("jim")
                .passwordHash(encoder.encode("current-password")).build();
        when(repository.findByUsername("jim")).thenReturn(Optional.of(user));

        assertMessage(() -> service.changePassword("jim", "wrong", "new-password-1",
                "new-password-1"), "not correct");

        service.changePassword("jim", "current-password", "new-password-1", "new-password-1");
        assertTrue(encoder.matches("new-password-1", user.getPasswordHash()));
        verify(repository).save(user);
    }

    @Test
    void findReadsTheAccountByName() {
        AppUser user = AppUser.builder().username("jim").build();
        when(repository.findByUsername("jim")).thenReturn(Optional.of(user));

        assertEquals(Optional.of(user), service.find(" Jim "));
        assertEquals(Optional.empty(), service.find(null));
    }

    @Test
    void changePasswordRefusesAnUnknownAccount() {
        when(repository.findByUsername("jim")).thenReturn(Optional.empty());

        assertMessage(() -> service.changePassword("jim", "current", "new-password-1",
                "new-password-1"), "No account named");
    }

    @Test
    void setPasswordValidatesTheSameRules() {
        AppUser user = AppUser.builder().username("jim").passwordHash("old").build();

        assertMessage(() -> service.setPassword(user, "short", "short"), "at least 10 characters");
        assertMessage(() -> service.setPassword(user, "long-password", "other"), "not the same");

        service.setPassword(user, "long-password", "long-password");
        assertTrue(encoder.matches("long-password", user.getPasswordHash()));
    }

    @Test
    void recordLoginNotesTheMoment() {
        AppUser user = AppUser.builder().username("jim").build();
        when(repository.findByUsername("jim")).thenReturn(Optional.of(user));

        service.recordLogin("Jim");

        assertNotNull(user.getLastLoginTime());
        verify(repository).save(user);
    }

    private void assertMessage(Runnable action, String expected) {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, action::run);
        assertTrue(error.getMessage().contains(expected),
                "expected the message to contain \"" + expected + "\" but was: " + error.getMessage());
    }
}
