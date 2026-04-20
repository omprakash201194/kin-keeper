package com.ogautam.kinkeeper.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A web-push subscription endpoint, one per (user, device/browser). The
 * endpoint + p256dh + auth triple is what the browser's Push API hands us
 * during Notification.permission-request; the backend replays it to send
 * pushes via VAPID.
 *
 * `id` is a hash of the endpoint so resubscribing the same device updates
 * in place instead of accumulating duplicates.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PushSubscription {
    private String id;
    private String userId;
    private String endpoint;
    private String p256dh;
    private String auth;
    private String userAgent;
    private Instant createdAt;
    private Instant lastPushAt;
    private Instant lastFailureAt;
    private String lastFailureReason;
}
