package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

    @GetMapping
    public ResponseEntity<?> get(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        // TODO: return user settings (hasApiKey, etc.)
        return ResponseEntity.ok(Map.of("hasApiKey", false));
    }

    @PutMapping("/api-key")
    public ResponseEntity<?> updateApiKey(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                          @RequestBody Map<String, String> body) {
        // TODO: encrypt and store API key in Firestore
        return ResponseEntity.ok(Map.of("status", "saved"));
    }
}
