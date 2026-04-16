package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.model.UserProfile;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserService userService;

    public AuthController(UserService userService) {
        this.userService = userService;
    }

    @PostMapping("/verify")
    public ResponseEntity<?> verify(@RequestBody Map<String, String> body) {
        // reason: frontend calls this after Firebase sign-in to create/sync user profile in Firestore
        // The Firebase ID token is validated by FirebaseAuthFilter before reaching here
        // For /verify specifically, we allow unauthenticated access and handle token internally
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        try {
            UserProfile profile = userService.createOrUpdateUser(principal);
            return ResponseEntity.ok(profile);
        } catch (Exception e) {
            log.error("Failed to get user profile", e);
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Failed to get user profile"));
        }
    }
}
