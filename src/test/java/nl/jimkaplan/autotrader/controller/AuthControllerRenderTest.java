package nl.jimkaplan.autotrader.controller;

import nl.jimkaplan.autotrader.model.document.AppUser;
import nl.jimkaplan.autotrader.service.AppUserService;
import nl.jimkaplan.autotrader.service.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The sign-in, signup and recovery pages: what they show in each state, and that a broken rule
 * comes back with its reason — and never with the submitted password.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(controllers = AuthController.class, properties = {
        "spring.data.mongodb.uri=mongodb://localhost:27017/autotrader-test",
        "bitvavo.api.url=http://localhost:9999",
        "encryption.master-key=dGVzdC1rZXk=",
        "autotrader.ui.time-zone=UTC"
})
class AuthControllerRenderTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppUserService users;
    @MockitoBean
    private PasswordResetService recovery;
    @MockitoBean
    private AuthenticationManager authenticationManager;

    @Test
    void theSignInPageShowsTheFormAndEachMessage() throws Exception {
        when(users.exists()).thenReturn(true);

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"password\"")))
                .andExpect(content().string(not(containsString("You are signed out."))));

        mockMvc.perform(get("/login").param("logout", ""))
                .andExpect(content().string(containsString("You are signed out.")));

        mockMvc.perform(get("/login").param("error", ""))
                .andExpect(content().string(containsString("do not match")));

        mockMvc.perform(get("/login").param("reset", ""))
                .andExpect(content().string(containsString("Your new password is set")));
    }

    @Test
    void aFreshInstallationGoesFromTheSignInPageToSignup() throws Exception {
        when(users.exists()).thenReturn(false);

        mockMvc.perform(get("/login"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/signup"));
    }

    @Test
    void signupIsOpenWhileNoAccountExists() throws Exception {
        when(users.exists()).thenReturn(false);

        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Create the account")))
                .andExpect(content().string(containsString("name=\"username\"")))
                .andExpect(content().string(containsString("name=\"email\"")))
                .andExpect(content().string(containsString("at least 10 characters")))
                .andExpect(content().string(containsString("Signups close after this one")));
    }

    @Test
    void signupIsClosedOnceTheAccountExists() throws Exception {
        when(users.exists()).thenReturn(true);

        mockMvc.perform(get("/signup"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Signups are closed")))
                .andExpect(content().string(containsString("Sign in instead")));
    }

    @Test
    void signupCreatesTheAccountAndSignsItIn() throws Exception {
        when(users.exists()).thenReturn(false);
        when(users.createFirst("jim", "jim@example.com", "long-password", "long-password", "Jim"))
                .thenReturn(AppUser.builder().id("u1").username("jim").build());
        when(authenticationManager.authenticate(any()))
                .thenReturn(new UsernamePasswordAuthenticationToken("jim", null));

        mockMvc.perform(post("/signup")
                        .param("username", "jim")
                        .param("email", "jim@example.com")
                        .param("displayName", "Jim")
                        .param("password", "long-password")
                        .param("confirm", "long-password"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/live"));

        verify(users).createFirst("jim", "jim@example.com", "long-password", "long-password", "Jim");
    }

    @Test
    void aBrokenRuleKeepsTheFormAndNeverEchoesThePassword() throws Exception {
        when(users.exists()).thenReturn(false);
        when(users.createFirst(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalArgumentException("The two passwords are not the same."));

        mockMvc.perform(post("/signup")
                        .param("username", "jim")
                        .param("email", "jim@example.com")
                        .param("password", "super-secret-password")
                        .param("confirm", "not-the-same"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The two passwords are not the same.")))
                .andExpect(content().string(containsString("value=\"jim\"")))
                .andExpect(content().string(not(containsString("super-secret-password"))));
    }

    @Test
    void theRecoveryRequestAnswersWithTheOneSentence() throws Exception {
        when(recovery.request("someone@example.com")).thenReturn(PasswordResetService.SENT_MESSAGE);

        mockMvc.perform(post("/forgot-password").param("email", "someone@example.com"))
                .andExpect(status().isOk())
                // The page renders the sentence with its apostrophe HTML-escaped.
                .andExpect(content().string(containsString(
                        "send a recovery email to this address if it exists")));
    }

    @Test
    void theResetPageSaysWhenTheLinkIsGone() throws Exception {
        when(recovery.valid("gone")).thenReturn(false);

        mockMvc.perform(get("/reset-password").param("token", "gone"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("no longer valid")))
                .andExpect(content().string(not(containsString("name=\"token\""))));
    }

    @Test
    void theResetPageShowsTheFormForALiveLink() throws Exception {
        when(recovery.valid("live")).thenReturn(true);

        mockMvc.perform(get("/reset-password").param("token", "live"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("name=\"token\"")))
                .andExpect(content().string(containsString("value=\"live\"")))
                .andExpect(content().string(containsString("New password")));
    }

    @Test
    void aResetSetsThePasswordAndReturnsToTheSignInPage() throws Exception {
        mockMvc.perform(post("/reset-password")
                        .param("token", "live")
                        .param("password", "new-password-1")
                        .param("confirm", "new-password-1"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?reset"));

        verify(recovery).reset("live", "new-password-1", "new-password-1");
    }

    @Test
    void aPasswordRuleFailureComesBackWithItsReason() throws Exception {
        doThrow(new IllegalArgumentException("The password must be at least 10 characters."))
                .when(recovery).reset("live", "short", "short");
        when(recovery.valid("live")).thenReturn(true);

        mockMvc.perform(post("/reset-password")
                        .param("token", "live")
                        .param("password", "short")
                        .param("confirm", "short"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("at least 10 characters")))
                .andExpect(content().string(containsString("value=\"live\"")))
                .andExpect(content().string(not(containsString("value=\"short\""))));
    }
}
