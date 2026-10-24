package nl.jimkaplan.autotrader.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import nl.jimkaplan.autotrader.service.AppUserService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.SavedRequestAwareAuthenticationSuccessHandler;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.io.IOException;
import java.util.Set;

/**
 * Who may reach what.
 *
 * <p>The pages sign in with a form and a session cookie; the JSON API keeps HTTP Basic with the
 * same account, so scripts and Postman keep working without a session. The TradingView webhook and
 * the Web Push service worker, manifest and icon are left open: the webhook authenticates itself
 * with its own webhook key, and the browser fetches the push assets before the page's session is
 * considered. They contain no account data. Nothing else is public: the pages, the API, the
 * exported OpenAPI files and the actuator endpoints behind them all ask for a sign-in.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * The paths that stay open: the sign-in and recovery pages (a locked-out reader has to reach
     * them), the stylesheet, the error page, the health probe for monitoring, the webhook and the
     * static Web Push assets the browser fetches on its own.
     */
    static final String[] OPEN_PATHS = {
            "/", "/login", "/signup", "/forgot-password", "/reset-password",
            "/css/**", "/js/**", "/images/**", "/favicon.ico", "/error",
            "/actuator/health", "/webhook/**",
            "/service-worker.js", "/manifest.webmanifest", "/icon.svg"
    };

    /**
     * How a loopback address may look: the exporter that writes the committed specification runs on
     * this machine and fetches {@code /v3/api-docs} over the loopback interface, so that one path
     * admits the host itself.
     *
     * <p>This is only trustworthy because the application runs with
     * {@code server.forward-headers-strategy=native}: Tomcat's valve rewrites the remote address
     * from {@code X-Forwarded-For} only when the peer is on the host itself (Caddy), and it ignores
     * the {@code Forwarded} header, so a remote caller cannot claim a loopback address in a header.
     * Spring's own {@code framework} strategy would let exactly that through.</p>
     */
    private static final Set<String> LOOPBACK_ADDRESSES = Set.of("127.0.0.1", "0:0:0:0:0:0:0:1");

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The {@code AuthenticationManager} the signup flow signs the new account in with.
     *
     * @param configuration Spring Security's authentication configuration
     * @return The manager built from the account service and the password encoder
     * @throws Exception when the configuration cannot build one
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration configuration)
            throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AppUserService users)
            throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(OPEN_PATHS).permitAll()
                        .requestMatchers("/v3/api-docs/**").access(loopbackOrSignedIn())
                        .anyRequest().authenticated())
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(loginSuccessHandler(users))
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login?logout")
                        .permitAll())
                .httpBasic(org.springframework.security.config.Customizer.withDefaults())
                // The API authenticates per request with Basic, and the webhook carries its own key,
                // so neither needs a CSRF token; the browser forms keep theirs.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**", "/webhook/**"))
                .exceptionHandling(handling -> handling
                        // A page redirects to the sign-in form; the API answers 401 so a script sees
                        // what happened instead of a login page.
                        .defaultAuthenticationEntryPointFor(
                                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                                new AntPathRequestMatcher("/api/**"))
                        .defaultAuthenticationEntryPointFor(
                                new LoginUrlAuthenticationEntryPoint("/login"),
                                new AntPathRequestMatcher("/**")));
        return http.build();
    }

    /**
     * @return A rule that admits a signed-in reader and, for the specification exporter, this
     *         machine itself — Swagger UI fetches its configuration as the reader's browser, which
     *         is not loopback
     */
    static AuthorizationManager<RequestAuthorizationContext> loopbackOrSignedIn() {
        return (authentication, context) -> new AuthorizationDecision(
                isLoopback(context.getRequest().getRemoteAddr()) || isSignedIn(authentication.get()));
    }

    private static boolean isLoopback(String address) {
        return address != null && (LOOPBACK_ADDRESSES.contains(address) || "::1".equals(address));
    }

    private static boolean isSignedIn(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    /**
     * Send a freshly signed-in reader to the live page and note the sign-in on the account.
     *
     * @param users The account service
     * @return The success handler
     */
    static SavedRequestAwareAuthenticationSuccessHandler loginSuccessHandler(AppUserService users) {
        SavedRequestAwareAuthenticationSuccessHandler handler =
                new SavedRequestAwareAuthenticationSuccessHandler() {
                    @Override
                    public void onAuthenticationSuccess(HttpServletRequest request,
                                                        HttpServletResponse response,
                                                        Authentication authentication)
                            throws IOException, ServletException {
                        users.recordLogin(authentication.getName());
                        super.onAuthenticationSuccess(request, response, authentication);
                    }
                };
        handler.setDefaultTargetUrl("/ui/live");
        handler.setAlwaysUseDefaultTargetUrl(true);
        return handler;
    }
}
