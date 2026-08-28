package com.nimbus.api.account;

import com.nimbus.api.jwt.JwtUtil;
import com.nimbus.api.security.SessionAuthFilter;
import com.nimbus.api.session.SessionService;
import com.nimbus.api.site.Site;
import com.nimbus.api.site.SiteRepository;
import com.nimbus.api.site.SiteStorageService;
import com.nimbus.api.session.ActiveSession;
import com.nimbus.api.ticket.TicketRepository;
import com.nimbus.api.user.User;
import com.nimbus.api.user.UserRepository;
import com.nimbus.api.user.UserResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Everything a signed-in user can do to their own account. */
@RestController
@RequestMapping("/me")
public class AccountController {

    private static final int MIN_PASSWORD_LENGTH = 10;

    private final UserRepository users;
    private final TicketRepository tickets;
    private final SiteRepository sites;
    private final SiteStorageService siteStorage;
    private final SessionService sessions;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AccountController(UserRepository users,
                             TicketRepository tickets,
                             SiteRepository sites,
                             SiteStorageService siteStorage,
                             SessionService sessions,
                             PasswordEncoder passwordEncoder,
                             JwtUtil jwtUtil) {
        this.users = users;
        this.tickets = tickets;
        this.sites = sites;
        this.siteStorage = siteStorage;
        this.sessions = sessions;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    // ------------------------------------------------------------- profile

    @GetMapping
    public Map<String, Object> me(Authentication authentication) {
        User user = require(authentication);

        List<Site> owned = sites.findByOwnerOrderByCreatedAtAsc(user);
        long used = owned.stream().mapToLong(s -> siteStorage.usedBytes(s.getSlug())).sum();
        long quota = user.getStorageQuotaBytes();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", user.getId());
        body.put("username", user.getUsername());
        body.put("createdAt", user.getCreatedAt());
        body.put("passwordChangedAt", user.getPasswordChangedAt());
        body.put("activeSessions", sessions.listFor(user).size());
        body.put("openTickets",
                tickets.countByOwnerAndStatusNot(user, com.nimbus.api.ticket.TicketStatus.CLOSED));
        body.put("siteCount", owned.size());
        body.put("storageQuotaBytes", quota);
        body.put("storageQuotaHuman", SiteStorageService.human(quota));
        body.put("storageUsedBytes", used);
        body.put("storageUsedHuman", SiteStorageService.human(used));
        body.put("storagePercentUsed", quota == 0 ? 0 : Math.min(100, (int) ((used * 100) / quota)));
        return body;
    }

    // ------------------------------------------------------------ password

    @PostMapping("/password")
    public ResponseEntity<?> changePassword(@RequestBody PasswordChangeRequest request,
                                            Authentication authentication,
                                            HttpServletRequest servletRequest) {

        User user = require(authentication);

        if (request.currentPassword() == null
                || !passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Current password is incorrect"));
        }

        String next = request.newPassword() == null ? "" : request.newPassword();
        if (next.length() < MIN_PASSWORD_LENGTH) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message",
                            "New password must be at least " + MIN_PASSWORD_LENGTH + " characters"));
        }
        if (passwordEncoder.matches(next, user.getPassword())) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "New password must be different from the current one"));
        }

        user.changePassword(passwordEncoder.encode(next));
        users.save(user);

        // A password change is how you recover from a compromise, so every other
        // session dies with it. The one making the request survives.
        Long keep = SessionAuthFilter.currentSession(servletRequest)
                .map(ActiveSession::id)
                .orElse(-1L);
        int revoked = sessions.revokeAllExcept(user, keep);

        return ResponseEntity.ok(Map.of(
                "status", "password changed",
                "otherSessionsRevoked", revoked
        ));
    }

    // ------------------------------------------------------------ sessions

    @GetMapping("/sessions")
    public List<Map<String, Object>> listSessions(Authentication authentication,
                                                  HttpServletRequest servletRequest) {
        User user = require(authentication);
        Long currentId = SessionAuthFilter.currentSession(servletRequest)
                .map(ActiveSession::id)
                .orElse(-1L);

        return sessions.listFor(user).stream()
                .map(session -> Map.<String, Object>of(
                        "id", session.getId(),
                        "current", session.getId().equals(currentId),
                        "createdAt", session.getCreatedAt(),
                        "lastSeenAt", session.getLastSeenAt(),
                        "expiresAt", session.getExpiresAt(),
                        "ip", session.getIp() == null ? "unknown" : session.getIp(),
                        "userAgent", session.getUserAgent() == null ? "unknown" : session.getUserAgent()
                ))
                .toList();
    }

    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<?> revokeSession(@PathVariable Long id, Authentication authentication) {
        User user = require(authentication);
        if (!sessions.revokeById(user, id)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "No such session"));
        }
        return ResponseEntity.ok(Map.of("status", "session revoked"));
    }

    @DeleteMapping("/sessions")
    public Map<String, Object> revokeOtherSessions(Authentication authentication,
                                                   HttpServletRequest servletRequest) {
        User user = require(authentication);
        Long keep = SessionAuthFilter.currentSession(servletRequest)
                .map(ActiveSession::id)
                .orElse(-1L);
        return Map.of("revoked", sessions.revokeAllExcept(user, keep));
    }

    // ----------------------------------------------------------- api token

    /**
     * Mints a bearer token for scripts and CI. Cookies are for browsers; this is
     * the escape hatch for everything else, and it is deliberately short-lived.
     */
    @PostMapping("/api-token")
    public Map<String, Object> apiToken(Authentication authentication) {
        User user = require(authentication);
        return Map.of(
                "token", jwtUtil.generateToken(user.getId(), user.getUsername()),
                "type", "Bearer",
                "issuedAt", Instant.now()
        );
    }

    // -------------------------------------------------------------- delete

    @PostMapping("/delete")
    @Transactional
    public ResponseEntity<?> deleteAccount(@RequestBody PasswordConfirmRequest request,
                                           Authentication authentication) {

        User user = require(authentication);

        if (request.password() == null
                || !passwordEncoder.matches(request.password(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("message", "Password is incorrect"));
        }

        // Children first — the FKs point at the user row. Site files live on disk
        // rather than in the database, so they need removing explicitly or the
        // bytes would stay billed against nobody.
        for (Site site : sites.findByOwnerOrderByCreatedAtAsc(user)) {
            siteStorage.destroy(site.getSlug());
        }
        sites.deleteAllByOwner(user);
        tickets.deleteAllByOwner(user);
        sessions.revokeAll(user);
        users.delete(user);

        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessions.clearingCookie().toString())
                .body(Map.of("status", "account deleted"));
    }

    // -------------------------------------------------------------- helper

    private User require(Authentication authentication) {
        return users.findByUsername(authentication.getName())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED, "Account no longer exists"));
    }

    public record PasswordChangeRequest(String currentPassword, String newPassword) {}

    public record PasswordConfirmRequest(String password) {}
}
