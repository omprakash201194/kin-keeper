package com.ogautam.kinkeeper.drive;

import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.drive.DriveScopes;
import com.ogautam.kinkeeper.crypto.CryptoService;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class DriveOAuthService {

    private static final List<String> SCOPES = Collections.singletonList(DriveScopes.DRIVE_FILE);
    private static final long STATE_MAX_AGE_SECONDS = 600;

    private final GoogleOAuthProperties props;
    private final CryptoService cryptoService;
    private GoogleAuthorizationCodeFlow flow;

    public DriveOAuthService(GoogleOAuthProperties props, CryptoService cryptoService) {
        this.props = props;
        this.cryptoService = cryptoService;
    }

    @PostConstruct
    void init() {
        if (!isConfigured()) {
            log.warn("Google OAuth not configured (GOOGLE_OAUTH_CLIENT_ID/SECRET/REDIRECT_URI missing). " +
                    "Drive endpoints will return 503 until configured.");
            return;
        }
        GoogleClientSecrets.Details details = new GoogleClientSecrets.Details()
                .setClientId(props.getClientId())
                .setClientSecret(props.getClientSecret());
        GoogleClientSecrets clientSecrets = new GoogleClientSecrets().setWeb(details);

        try {
            this.flow = new GoogleAuthorizationCodeFlow.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    clientSecrets,
                    SCOPES)
                    .setAccessType("offline")
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Google OAuth flow", e);
        }
    }

    public boolean isConfigured() {
        return notBlank(props.getClientId())
                && notBlank(props.getClientSecret())
                && notBlank(props.getRedirectUri());
    }

    public String buildAuthUrl(String uid) {
        requireConfigured();
        String state = signState(uid);
        return flow.newAuthorizationUrl()
                .setRedirectUri(props.getRedirectUri())
                .setState(state)
                .set("prompt", "consent")
                .build();
    }

    public String exchangeCodeForRefreshToken(String code) {
        requireConfigured();
        try {
            GoogleTokenResponse token = flow.newTokenRequest(code)
                    .setRedirectUri(props.getRedirectUri())
                    .execute();
            String refresh = token.getRefreshToken();
            if (refresh == null || refresh.isBlank()) {
                throw new IllegalStateException(
                        "Google did not return a refresh token. " +
                        "Did you set access_type=offline and prompt=consent? " +
                        "If the user has already authorized this client, revoke access at " +
                        "myaccount.google.com and try again.");
            }
            return refresh;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to exchange code for tokens: " + e.getMessage(), e);
        }
    }

    public String verifyStateAndExtractUid(String state) {
        if (state == null || state.isBlank()) {
            throw new IllegalArgumentException("Missing state");
        }
        int dot = state.lastIndexOf('.');
        if (dot < 0) {
            throw new IllegalArgumentException("Malformed state");
        }
        String payloadB64 = state.substring(0, dot);
        String signatureB64 = state.substring(dot + 1);

        String expectedSig = hmacSha256Base64Url(payloadB64);
        if (!constantTimeEquals(expectedSig, signatureB64)) {
            throw new IllegalArgumentException("Invalid state signature");
        }

        String payload = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
        String[] parts = payload.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Malformed state payload");
        }
        String uid = parts[0];
        long ts;
        try {
            ts = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Malformed state timestamp");
        }
        long age = Instant.now().getEpochSecond() - ts;
        if (age > STATE_MAX_AGE_SECONDS || age < -60) {
            throw new IllegalArgumentException("State expired; please try again");
        }
        return uid;
    }

    public String getFrontendRedirect() {
        return props.getFrontendRedirect();
    }

    private String signState(String uid) {
        String payload = uid + ":" + Instant.now().getEpochSecond();
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String sig = hmacSha256Base64Url(payloadB64);
        return payloadB64 + "." + sig;
    }

    private String hmacSha256Base64Url(String payloadB64) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(cryptoService.stateSigningKey(), "HmacSHA256"));
            byte[] sig = mac.doFinal(payloadB64.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign state", e);
        }
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }

    private void requireConfigured() {
        if (!isConfigured()) {
            throw new IllegalStateException("Google OAuth is not configured on the server");
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
