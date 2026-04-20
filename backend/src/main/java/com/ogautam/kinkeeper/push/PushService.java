package com.ogautam.kinkeeper.push;

import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.ogautam.kinkeeper.model.PushSubscription;
import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Notification;
import org.apache.http.HttpResponse;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Security;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Stores browser push subscriptions and sends notifications via VAPID.
 *
 * One subscription per (userId, endpoint); we key the Firestore doc by a
 * stable hash of the endpoint URL so re-subscribing the same device updates
 * in place. Failed pushes with HTTP 404/410 are treated as "the browser
 * cleared the subscription" — we delete the doc so we stop hammering a dead
 * endpoint.
 */
@Slf4j
@Service
public class PushService {

    private static final String COLLECTION = "push_subscriptions";

    static {
        // web-push needs BouncyCastle for the ECDH handshake; register once at
        // class load so individual send()s don't race on Security.addProvider.
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        }
    }

    private final Firestore firestore;
    private final PushProperties props;

    public PushService(Firestore firestore, PushProperties props) {
        this.firestore = firestore;
        this.props = props;
    }

    public PushSubscription subscribe(String userId, String endpoint, String p256dh,
                                       String auth, String userAgent)
            throws ExecutionException, InterruptedException {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("endpoint is required");
        }
        String id = hashEndpoint(endpoint);
        Instant now = Instant.now();
        PushSubscription sub = PushSubscription.builder()
                .id(id)
                .userId(userId)
                .endpoint(endpoint)
                .p256dh(p256dh)
                .auth(auth)
                .userAgent(userAgent)
                .createdAt(now)
                .build();
        firestore.collection(COLLECTION).document(id).set(sub).get();
        log.info("Subscribed push endpoint {} for user {} ua='{}'", id, userId, userAgent);
        return sub;
    }

    public void unsubscribe(String userId, String endpoint)
            throws ExecutionException, InterruptedException {
        if (endpoint == null || endpoint.isBlank()) return;
        String id = hashEndpoint(endpoint);
        DocumentSnapshot snap = firestore.collection(COLLECTION).document(id).get().get();
        if (!snap.exists()) return;
        // Only the owner can unsubscribe their own device.
        String owner = snap.getString("userId");
        if (owner != null && !owner.equals(userId)) return;
        firestore.collection(COLLECTION).document(id).delete().get();
        log.info("Unsubscribed push endpoint {} for user {}", id, userId);
    }

    public List<PushSubscription> listForUser(String userId)
            throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION)
                .whereEqualTo("userId", userId).get().get().getDocuments();
        List<PushSubscription> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) out.add(d.toObject(PushSubscription.class));
        return out;
    }

    public List<PushSubscription> listAll() throws ExecutionException, InterruptedException {
        List<QueryDocumentSnapshot> docs = firestore.collection(COLLECTION).get().get().getDocuments();
        List<PushSubscription> out = new ArrayList<>(docs.size());
        for (QueryDocumentSnapshot d : docs) out.add(d.toObject(PushSubscription.class));
        return out;
    }

    /**
     * Send a notification to one subscription. Returns the HTTP status so callers
     * can act on failures (e.g. purge dead subs). Swallows and logs any exception;
     * never throws so a misbehaving endpoint doesn't take out the whole digest run.
     */
    public int send(PushSubscription sub, String title, String body, String link) {
        if (!props.isEnabled()) {
            log.warn("Push send skipped — VAPID not configured");
            return -1;
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("title", title);
            payload.put("body", body);
            if (link != null) payload.put("link", link);
            String json = "{"
                    + "\"title\":"  + quote(title) + ","
                    + "\"body\":"   + quote(body)  + ","
                    + "\"link\":"   + quote(link != null ? link : "/")
                    + "}";

            nl.martijndwars.webpush.PushService pushService =
                    new nl.martijndwars.webpush.PushService(props.getPublicKey(),
                            props.getPrivateKey(), props.getSubject());
            Notification notification = new Notification(
                    sub.getEndpoint(),
                    sub.getP256dh(),
                    sub.getAuth(),
                    json.getBytes(StandardCharsets.UTF_8));
            HttpResponse resp = pushService.send(notification);
            int status = resp.getStatusLine().getStatusCode();
            DocumentReference ref = firestore.collection(COLLECTION).document(sub.getId());
            if (status == 404 || status == 410) {
                // Endpoint dead — the browser cleared it. Delete so we stop trying.
                ref.delete().get();
                log.info("Purged dead push subscription {} (status {})", sub.getId(), status);
            } else if (status >= 200 && status < 300) {
                ref.update("lastPushAt", Instant.now(), "lastFailureReason", null).get();
            } else {
                ref.update("lastFailureAt", Instant.now(),
                        "lastFailureReason", "HTTP " + status).get();
                log.warn("Push returned HTTP {} for {}", status, sub.getId());
            }
            return status;
        } catch (Exception e) {
            log.warn("Push send failed for {}: {}", sub.getId(), e.getMessage());
            try {
                firestore.collection(COLLECTION).document(sub.getId()).update(
                        "lastFailureAt", Instant.now(),
                        "lastFailureReason", e.getClass().getSimpleName() + ": " + e.getMessage()).get();
            } catch (Exception ignored) { /* best effort */ }
            return -1;
        }
    }

    /** Fire a notification to every device the user has registered. */
    public int sendToUser(String userId, String title, String body, String link)
            throws ExecutionException, InterruptedException {
        int sent = 0;
        for (PushSubscription sub : listForUser(userId)) {
            int status = send(sub, title, body, link);
            if (status >= 200 && status < 300) sent++;
        }
        return sent;
    }

    private static String hashEndpoint(String endpoint) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(endpoint.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (Exception e) {
            // SHA-256 is always available; only path here is truly catastrophic.
            return String.valueOf(endpoint.hashCode());
        }
    }

    private static String quote(String s) {
        if (s == null) return "null";
        return '"' + s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + '"';
    }
}
