package com.nimbus.api.security;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Hands the front end its CSRF token. The value also arrives as the readable
 * XSRF-TOKEN cookie; this endpoint exists so a page can fetch one explicitly
 * before its first state-changing request.
 */
@RestController
public class CsrfController {

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of(
                "token", token.getToken(),
                "headerName", token.getHeaderName()
        );
    }
}
