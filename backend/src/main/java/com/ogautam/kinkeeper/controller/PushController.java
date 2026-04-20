package com.ogautam.kinkeeper.controller;

import com.ogautam.kinkeeper.push.PushProperties;
import com.ogautam.kinkeeper.push.PushService;
import com.ogautam.kinkeeper.security.FirebaseUserPrincipal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushService pushService;
    private final PushProperties pushProps;

    public PushController(PushService pushService, PushProperties pushProps) {
        this.pushService = pushService;
        this.pushProps = pushProps;
    }

    /**
     * Returns the VAPID public key the browser needs to subscribe, and a
     * feature flag the frontend uses to decide whether to show the toggle.
     */
    @GetMapping("/config")
    public ResponseEntity<?> config() {
        return ResponseEntity.ok(Map.of(
                "enabled", pushProps.isEnabled(),
                "publicKey", pushProps.isEnabled() ? pushProps.getPublicKey() : ""));
    }

    @PostMapping("/subscribe")
    public ResponseEntity<?> subscribe(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                       @RequestBody SubscribeBody body,
                                       @RequestHeader(value = "User-Agent", required = false) String userAgent)
            throws Exception {
        if (!pushProps.isEnabled()) {
            throw new IllegalStateException("Push notifications are not configured on the server");
        }
        if (body == null || body.endpoint() == null || body.keys() == null) {
            throw new IllegalArgumentException("endpoint and keys are required");
        }
        return ResponseEntity.ok(pushService.subscribe(
                principal.uid(), body.endpoint(),
                body.keys().p256dh(), body.keys().auth(),
                userAgent == null ? "" : userAgent));
    }

    @PostMapping("/unsubscribe")
    public ResponseEntity<?> unsubscribe(@AuthenticationPrincipal FirebaseUserPrincipal principal,
                                         @RequestBody UnsubscribeBody body) throws Exception {
        pushService.unsubscribe(principal.uid(), body == null ? null : body.endpoint());
        return ResponseEntity.ok(Map.of("status", "unsubscribed"));
    }

    /** Fire a test notification to every device the caller has subscribed. Handy for verifying the wiring. */
    @PostMapping("/test")
    public ResponseEntity<?> test(@AuthenticationPrincipal FirebaseUserPrincipal principal) throws Exception {
        int sent = pushService.sendToUser(principal.uid(),
                "Kin-Keeper is connected",
                "Push notifications are working on this device.",
                "/reminders");
        return ResponseEntity.ok(Map.of("sent", sent));
    }

    public record SubscribeBody(String endpoint, Keys keys) {
        public record Keys(String p256dh, String auth) {}
    }
    public record UnsubscribeBody(String endpoint) {}
}
