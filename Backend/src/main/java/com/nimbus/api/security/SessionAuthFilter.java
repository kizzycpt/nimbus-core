package com.nimbus.api.security;

import com.nimbus.api.session.SessionService;
import com.nimbus.api.session.ActiveSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Authenticates a request from the HttpOnly session cookie.
 *
 * Runs before the bearer-token filter: a browser session always wins over a
 * stray Authorization header. An absent or invalid cookie is not an error here
 * — the request simply stays anonymous and the authorization rules decide.
 */
public class SessionAuthFilter extends OncePerRequestFilter {

    /** Request attribute holding the live session, for controllers that need it. */
    public static final String SESSION_ATTRIBUTE = "nimbus.session";

    private final SessionService sessions;

    public SessionAuthFilter(SessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            readCookie(request).flatMap(sessions::resolve).ifPresent(session -> {
                var authentication = new UsernamePasswordAuthenticationToken(
                        session.username(),
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_USER"))
                );
                SecurityContextHolder.getContext().setAuthentication(authentication);
                request.setAttribute(SESSION_ATTRIBUTE, session);
            });
        }

        chain.doFilter(request, response);
    }

    private Optional<String> readCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        for (Cookie cookie : cookies) {
            if (SessionService.COOKIE_NAME.equals(cookie.getName())) {
                return Optional.ofNullable(cookie.getValue());
            }
        }
        return Optional.empty();
    }

    /** Exposes the session bound to the current request, if any. */
    public static Optional<ActiveSession> currentSession(HttpServletRequest request) {
        Object attribute = request.getAttribute(SESSION_ATTRIBUTE);
        return attribute instanceof ActiveSession session ? Optional.of(session) : Optional.empty();
    }
}
