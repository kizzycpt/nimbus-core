package com.bereket.secure_api.user;

import org.springframework.security.core.Authentication;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import com.bereket.secure_api.jwt.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;

@RestController
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public UserController(UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }
    @GetMapping("/me")
    public Map<String, String> me(Authentication authentication) {
        return Map.of("username", authentication.getName());
    }
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody User user) {
        try {
            String hashedPassword = passwordEncoder.encode(user.getPassword());

            User newUser = new User(
                user.getUsername(),
                hashedPassword
            );

            User savedUser = userRepository.save(newUser);

            return ResponseEntity.ok(
                new UserResponse(
                    savedUser.getId(),
                    savedUser.getUsername()
                )
            );
        } catch (DataIntegrityViolationException e) {
            return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body("Username already exists");
        }
    }
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request) {
        return userRepository.findByUsername(request.getUsername())
            .filter(user ->
                passwordEncoder.matches(
                    request.getPassword(),
                    user.getPassword()
                )
            )
            .map(user -> {
                String token = jwtUtil.generateToken(user.getId(), user.getUsername());
                return ResponseEntity.ok(Map.of("token", token));
            })
            .orElse(ResponseEntity.status(401).build());
    }
}
