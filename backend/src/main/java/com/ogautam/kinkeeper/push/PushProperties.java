package com.ogautam.kinkeeper.push;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * VAPID + notification config. Public/private keys are a one-time generated
 * pair (see scripts/generate-vapid.sh); subject is a mailto:/https: URL
 * Anthropic-style push services use to contact you if your key abuses the
 * service. `enabled` lets the feature be turned off cleanly when the keys
 * aren't configured — no crashes, the controller just reports 'unavailable'.
 */
@Data
@Component
@ConfigurationProperties(prefix = "webpush")
public class PushProperties {
    /** Base64-URL-encoded VAPID public key, handed to the browser for subscribe(). */
    private String publicKey;
    /** Base64-URL-encoded VAPID private key, used server-side to sign pushes. */
    private String privateKey;
    /** Contact URL (mailto: or https://) — appears in push request headers. */
    private String subject = "mailto:admin@kin-keeper.local";

    public boolean isEnabled() {
        return publicKey != null && !publicKey.isBlank()
                && privateKey != null && !privateKey.isBlank();
    }
}
