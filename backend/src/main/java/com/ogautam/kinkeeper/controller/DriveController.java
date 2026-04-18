package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.drive.DriveOAuthService;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import com.ogautam.kinkeeper.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/drive")
public class DriveController {

    private final DriveOAuthService oauthService;
    private final UserService userService;

    public DriveController(DriveOAuthService oauthService, UserService userService) {
        this.oauthService = oauthService;
        this.userService = userService;
    }

    @GetMapping("/status")
    public ResponseEntity<?> status(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        return ResponseEntity.ok(Map.of(
                "connected", userService.hasDriveRefreshToken(principal.uid()),
                "configured", oauthService.isConfigured()
        ));
    }

    @GetMapping("/connect")
    public ResponseEntity<?> connect(@AuthenticationPrincipal FirebaseUserPrincipal principal) {
        if (!oauthService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Google OAuth is not configured on the server"));
        }
        String url = oauthService.buildAuthUrl(principal.uid());
        return ResponseEntity.ok(Map.of("url", url));
    }

    @DeleteMapping("/connect")
    public ResponseEntity<?> disconnect(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        userService.deleteDriveRefreshToken(principal.uid());
        return ResponseEntity.ok(Map.of("status", "disconnected"));
    }

    // Public endpoint — Google redirects the user's browser here.
    // Authentication comes from the signed `state` parameter, not the Firebase token.
    @GetMapping("/callback")
    public void callback(@RequestParam(required = false) String code,
                         @RequestParam(required = false) String state,
                         @RequestParam(required = false) String error,
                         HttpServletResponse response) throws IOException {
        String frontend = oauthService.getFrontendRedirect();

        if (error != null) {
            response.sendRedirect(frontend + "?driveError=" + URLEncoder.encode(error, StandardCharsets.UTF_8));
            return;
        }
        if (code == null || state == null) {
            response.sendRedirect(frontend + "?driveError=missing_code_or_state");
            return;
        }

        try {
            String uid = oauthService.verifyStateAndExtractUid(state);
            String refreshToken = oauthService.exchangeCodeForRefreshToken(code);
            userService.saveDriveRefreshToken(uid, refreshToken);
            response.sendRedirect(frontend + "?driveConnected=1");
        } catch (Exception e) {
            log.warn("Drive OAuth callback failed: {}", e.getMessage());
            response.sendRedirect(frontend + "?driveError="
                    + URLEncoder.encode(e.getMessage() == null ? "unknown" : e.getMessage(), StandardCharsets.UTF_8));
        }
    }
}
