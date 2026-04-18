package com.ogautam.kinkeeper.drive;

import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Data
@Component
public class GoogleOAuthProperties {

    @Value("${GOOGLE_OAUTH_CLIENT_ID:}")
    private String clientId;

    @Value("${GOOGLE_OAUTH_CLIENT_SECRET:}")
    private String clientSecret;

    @Value("${GOOGLE_OAUTH_REDIRECT_URI:}")
    private String redirectUri;

    @Value("${GOOGLE_OAUTH_FRONTEND_REDIRECT:/settings}")
    private String frontendRedirect;
}
