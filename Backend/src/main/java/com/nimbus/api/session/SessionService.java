package com.nimbus.api.session;

import com.nimbus.api.user.User;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Issues and validates opaque session tokens.
 *
 * The token lives in an HttpOnly cookie, so page JavaScript can never read it —
 * an XSS bug cannot exfiltrate a login. Because the session is a database row
 * rather than a self-contained JWT, it can also be revoked instantly.
 */
@Service
public class SessionService {

    public static final String COOKIE_NAME = "nimbus_session";

    /** 256 bits of entropy — not guessable, not enumerable. */
    private static final int TOKEN_BYTES = 32;

    private final SessionRepository sessions;
    private final SecureRandom random = new SecureRandom();
    private final Duration ttl;
    private final boolean secureCookie;

    public SessionService(
            SessionRepository sessions,
            @Value("${app.session.ttl-minutes:720}") long ttlMinutes,
            @Value("${app.security.secure-cookies:false}") boolean secureCookie
    ) {
        this.sessions = sessions;
        this.ttl = Duration.ofMinutes(ttlMinutes);
        this.secureCookie = secureCookie;
    }

    // ---------------------------------------------------------------- issue

    @Transactional
    public String issue(User user, HttpServletRequest request) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        Instant now = Instant.now();
        sessions.save(new SessionToken(
                hash(token),
                user,
                now,
                now.plus(ttl),
                truncate(request.getHeader("User-Agent"), 255),
                truncate(clientIp(request), 45)
        ));
        return token;
    }

    /**
     * Resolves a raw cookie value to a live session, sliding its expiry forward.
     * Returns empty for unknown, expired, or malformed tokens.
     *
     * The owning user is read inside this transaction and returned by value —
     * the caller is a servlet filter with no persistence context of its own.
     */
    @Transactional
    public Optional<ActiveSession> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Optional<SessionToken> found = sessions.findByTokenHash(hash(token));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SessionToken session = found.get();
        Instant now = Instant.now();
        if (session.isExpired(now)) {
            sessions.delete(session);
            return Optional.empty();
        }
        session.touch(now, now.plus(ttl));

        User owner = session.getUser();
        return Optional.of(new ActiveSession(session.getId(), owner.getId(), owner.getUsername()));
    }

    // --------------------------------------------------------------- revoke

    @Transactional
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            sessions.deleteByTokenHash(hash(token));
        }
    }

    @Transactional
    public int revokeAll(User user) {
        return sessions.deleteAllForUser(user);
    }

    @Transactional
    public int revokeAllExcept(User user, Long keepSessionId) {
        return sessions.deleteAllForUserExcept(user, keepSessionId);
    }

    @Transactional
    public boolean revokeById(User user, Long sessionId) {
        return sessions.findById(sessionId)
                .filter(s -> s.getUser().getId().equals(user.getId()))
                .map(s -> { sessions.delete(s); return true; })
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<SessionToken> listFor(User user) {
        return sessions.findByUserOrderByLastSeenAtDesc(user);
    }

    /** Expired rows are dead weight; clear them out hourly. */
    @Scheduled(fixedDelay = 3_600_000L, initialDelay = 60_000L)
    @Transactional
    public void purgeExpired() {
        sessions.deleteExpired(Instant.now());
    }

    // -------------------------------------------------------------- cookies

    public ResponseCookie cookieFor(String token) {
        return baseCookie(token).maxAge(ttl).build();
    }

    /** A already-expired cookie with an empty value clears the browser's copy. */
    public ResponseCookie clearingCookie() {
        return baseCookie("").maxAge(0).build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)       // unreadable from JavaScript
                .secure(secureCookie) // HTTPS-only once a TLS front end is in place
                .sameSite("Strict")   // never sent on a cross-site request
                .path("/");
    }

    // --------------------------------------------------------------- helpers

    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the JDK spec", e);
        }
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
