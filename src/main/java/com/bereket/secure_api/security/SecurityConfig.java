package com.bereket.secure_api.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.bereket.secure_api.jwt.JwtAuthFilter;
import com.bereket.secure_api.jwt.JwtUtil;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtUtil jwtUtil
    ) throws Exception {

        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/health", "/register", "/login").permitAll()
                .anyRequest().authenticated()
            )
            .httpBasic(h -> h.disable())
            .formLogin(f -> f.disable());
            .addFilterBefore(
                new JwtAuthFilter(jwtUtil.getSecretKey()),
                UsernamePasswordAuthenticationFilter.class
            );

        return http.build();
    }
}
