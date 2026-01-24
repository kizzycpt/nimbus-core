package com.bereket.secure_api.user;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import com.bereket.secure_api.jwt.JwtUtil;

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

    @PostMapping("/register")
    public UserResponse register(@RequestBody User user) {
        String hashedPassword =
        passwordEncoder.encode(user.getPassword());

        User newUser = new User(
        user.getUsername(),
        hashedPassword
        );

        User savedUser = userRepository.save(newUser);

        return new UserResponse(
        savedUser.getId(),
        savedUser.getUsername()
        );
    }
    @PostMapping("/login")
    public ResponseEntity<String> login(@RequestBody LoginRequest request) {
        return userRepository.findByUsername(request.getUsername())
            .filter(user ->
                passwordEncoder.matches(
                    request.getPassword(),
                    user.getPassword()
                )
            )
            .map(user -> {
                String token = jwtUtil.generateToken(user.getId(), user.getUsername());
                return ResponseEntity.ok(token);
            })
            .orElse(ResponseEntity.status(401).build());
    }
}
