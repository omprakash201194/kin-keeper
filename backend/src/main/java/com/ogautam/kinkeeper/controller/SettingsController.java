package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    private final UserService userService;

    public SettingsController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public ResponseEntity<?> get(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        return ResponseEntity.ok(Map.of("hasApiKey", userService.hasApiKey(principal.uid())));
    }

    @PutMapping("/api-key")
    public ResponseEntity<?> updateApiKey(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                          @RequestBody Map<String, String> body) throws Exception {
        String apiKey = body.get("apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("apiKey is required");
        }
        userService.saveApiKey(principal.uid(), apiKey);
        return ResponseEntity.ok(Map.of("status", "saved"));
    }

    @DeleteMapping("/api-key")
    public ResponseEntity<?> deleteApiKey(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        userService.deleteApiKey(principal.uid());
        return ResponseEntity.ok(Map.of("status", "deleted"));
    }
}
