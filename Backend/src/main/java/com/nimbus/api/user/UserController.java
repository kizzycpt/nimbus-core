package com.nimbus.api.user;

import com.nimbus.api.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@RestController
public class UserController {

    /** Deliberately narrow: these end up in URLs, logs and ticket listings. */
    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{3,32}$");
    private static final int MIN_PASSWORD_LENGTH = 10;

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessions;
    private final long defaultStorageQuota;

    public UserController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          SessionService sessions,
                          @Value("${app.sites.default-quota-bytes:104857600}") long defaultStorageQuota) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.defaultStorageQuota = defaultStorageQuota;
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody LoginRequest request,
                                      HttpServletRequest servletRequest) {

        String username = request.getUsername() == null ? "" : request.getUsername().trim();
        String password = request.getPassword() == null ? "" : request.getPassword();

        if (!USERNAME.matcher(username).matches()) {
            return error(HttpStatus.BAD_REQUEST,
                    "Username must be 3-32 characters, letters/digits/dot/underscore/hyphen only");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            return error(HttpStatus.BAD_REQUEST,
                    "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }

        try {
            User account = new User(username, passwordEncoder.encode(password));
            account.setStorageQuotaBytes(defaultStorageQuota);
            User saved = userRepository.save(account);

            // Registering logs you straight in — one fewer round trip, and the
            // cookie is issued through exactly the same path as a normal login.
            String token = sessions.issue(saved, servletRequest);
            return ResponseEntity.status(HttpStatus.CREATED)
                    .header(HttpHeaders.SET_COOKIE, sessions.cookieFor(token).toString())
                    .body(UserResponse.of(saved));

        } catch (DataIntegrityViolationException e) {
            return error(HttpStatus.CONFLICT, "Username already exists");
        }
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request,
                                   HttpServletRequest servletRequest) {

        if (!StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            return error(HttpStatus.BAD_REQUEST, "Username and password are required");
        }

        Optional<User> found = userRepository.findByUsername(request.getUsername().trim());

        if (found.isEmpty()) {
            // Burn an equivalent BCrypt round anyway. Returning early here would
            // make "no such user" measurably faster than "wrong password" and
            // leak which usernames exist.
            passwordEncoder.encode(request.getPassword());
            return error(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        User user = found.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return error(HttpStatus.UNAUTHORIZED, "Invalid username or password");
        }

        String token = sessions.issue(user, servletRequest);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessions.cookieFor(token).toString())
                .body(UserResponse.of(user));
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request) {
        readSessionCookie(request).ifPresent(sessions::revoke);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessions.clearingCookie().toString())
                .body(Map.of("status", "logged out"));
    }

    private static Optional<String> readSessionCookie(HttpServletRequest request) {
        if (request.getCookies() == null) {
            return Optional.empty();
        }
        for (var cookie : request.getCookies()) {
            if (SessionService.COOKIE_NAME.equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    private static ResponseEntity<Map<String, String>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of("message", message));
    }
}
