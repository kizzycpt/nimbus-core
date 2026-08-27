package com.nimbus.api.user;

import org.springframework.security.core.Authentication;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import com.nimbus.api.jwt.JwtUtil;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.util.StringUtils;
import java.util.Optional;

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
        if (!StringUtils.hasText(user.getUsername()) || !StringUtils.hasText(user.getPassword())) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body("Username and password must not be empty");
        }
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
        if (!StringUtils.hasText(request.getUsername()) || !StringUtils.hasText(request.getPassword())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        Optional<User> found = userRepository.findByUsername(request.getUsername());

        if (found.isEmpty()) {
            // Burn an equivalent BCrypt round anyway. Returning early here would
            // make "no such user" measurably faster than "wrong password" and
            // leak which usernames exist.
            passwordEncoder.encode(request.getPassword());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        User user = found.get();
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        String token = jwtUtil.generateToken(user.getId(), user.getUsername());
        return ResponseEntity.ok(Map.of("token", token));
    }
}
