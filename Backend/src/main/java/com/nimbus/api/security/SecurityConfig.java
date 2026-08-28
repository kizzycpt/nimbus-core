package com.nimbus.api.security;

import com.nimbus.api.jwt.JwtAuthFilter;
import com.nimbus.api.jwt.JwtUtil;
import com.nimbus.api.session.SessionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.security.SecureRandom;
import java.util.List;

@Configuration
public class SecurityConfig {

    /**
     * Origins allowed to call the API cross-site. When the GUI is served by the
     * bundled nginx (same origin, /api proxy) this can be left empty.
     */
    private final List<String> allowedOriginPatterns;
    private final boolean secureCookies;
    private final int bcryptStrength;

    public SecurityConfig(
            @Value("${app.cors.allowed-origin-patterns:}") List<String> allowedOriginPatterns,
            @Value("${app.security.secure-cookies:false}") boolean secureCookies,
            @Value("${app.security.bcrypt-strength:12}") int bcryptStrength
    ) {
        this.allowedOriginPatterns = allowedOriginPatterns;
        this.secureCookies = secureCookies;
        this.bcryptStrength = bcryptStrength;
    }

    /**
     * BCrypt generates a fresh 16-byte random salt per password and stores it in
     * the hash, so two accounts with the same password never share a hash and
     * precomputed tables are useless.
     *
     * Strength is the log2 of the round count: 12 means 4096 rounds, roughly
     * 250ms on a modern x86 core and noticeably more on a small ARM board. That
     * cost is the entire point — it is what makes offline cracking expensive —
     * but it is configurable so a slow board can drop to 10 rather than make
     * every login feel broken. Existing hashes keep verifying either way, since
     * BCrypt reads the cost from the stored hash.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(bcryptStrength, new SecureRandom());
    }

    /**
     * There are no form-login users: authentication comes from the users table by
     * way of session cookies. Declaring this bean also stops Boot's default
     * auto-configuration from inventing a user and logging a random password.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> {
            throw new UsernameNotFoundException("No such user: " + username);
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtUtil jwtUtil,
            SessionService sessionService
    ) throws Exception {

        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository())
                // The plain handler (rather than the XOR-masking default) keeps the
                // cookie value and the expected header value identical, which is what
                // the double-submit pattern in the browser needs.
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                // A bearer token is not ambient credentials — the browser never
                // attaches it automatically, so those requests cannot be forged.
                .ignoringRequestMatchers(request -> {
                    String header = request.getHeader("Authorization");
                    return header != null && header.startsWith("Bearer ");
                })
            )
            .headers(headers -> headers
                .frameOptions(frame -> frame.deny())
                .httpStrictTransportSecurity(hsts -> hsts
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31_536_000))
                .referrerPolicy(referrer -> referrer
                    .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
            )
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm ->
                sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/error").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers(HttpMethod.GET,
                        "/health", "/status", "/actuator/health", "/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/register", "/login").permitAll()
                .anyRequest().authenticated()
            )
            // Without this an anonymous request to a protected path answers 403,
            // which tells a client "forbidden" when the truth is "not signed in".
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, failure) ->
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Authentication required"))
            )
            .httpBasic(h -> h.disable())
            .formLogin(f -> f.disable())
            .logout(l -> l.disable())
            // Must sit after the authentication stage: when a request arrives with
            // a session cookie, SessionManagementFilter fires CsrfAuthenticationStrategy,
            // which deletes the CSRF cookie and only *defers* minting its replacement.
            // Running last is what forces that replacement to actually be written.
            .addFilterBefore(new CsrfCookieFilter(), AuthorizationFilter.class)
            .addFilterBefore(
                new SessionAuthFilter(sessionService),
                UsernamePasswordAuthenticationFilter.class
            )
            .addFilterBefore(
                new JwtAuthFilter(jwtUtil.getSecretKey()),
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
                .sameSite("Strict")
                .secure(secureCookies)
                .path("/"));
        return repository;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        UrlBasedCorsConfigurationSource source =
            new UrlBasedCorsConfigurationSource();

        if (allowedOriginPatterns.isEmpty()) {
            // Same-origin deployment: no cross-site access granted at all.
            return source;
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(allowedOriginPatterns);
        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-XSRF-TOKEN"));
        // Cookies are same-site only; a cross-origin caller must use a bearer token.
        config.setAllowCredentials(false);
        config.setMaxAge(3600L);

        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
