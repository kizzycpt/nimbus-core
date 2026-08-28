package com.nimbus.api.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Spring Security defers CSRF token generation until something asks for it, so
 * the XSRF-TOKEN cookie would otherwise never be written on a plain page load.
 * Touching getToken() here forces the repository to issue it, which is what lets
 * the front end read it back and echo it in a header.
 *
 * Placement matters: this has to run after authentication. Once a session cookie
 * authenticates a request, CsrfAuthenticationStrategy rotates the CSRF token by
 * deleting the cookie and deferring the new one. Resolving the token here, at the
 * end of the chain, is what turns that deferral into an actual Set-Cookie.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
