package com.ogautam.kinkeeper.config;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.Firestore;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.cloud.FirestoreClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.FileInputStream;
import java.io.IOException;

@Slf4j
@Configuration
public class FirebaseConfig {

    private final FirebaseProperties properties;

    public FirebaseConfig(FirebaseProperties properties) {
        this.properties = properties;
    }

    @Bean
    public FirebaseApp firebaseApp() throws IOException {
        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseOptions.Builder builder = FirebaseOptions.builder();

            if (properties.getCredentialsPath() != null && !properties.getCredentialsPath().isBlank()) {
                builder.setCredentials(GoogleCredentials.fromStream(
                        new FileInputStream(properties.getCredentialsPath())));
                log.info("Firebase initialized with service account credentials");
            } else {
                builder.setCredentials(GoogleCredentials.getApplicationDefault());
                log.info("Firebase initialized with application default credentials");
            }

            if (properties.getProjectId() != null && !properties.getProjectId().isBlank()) {
                builder.setProjectId(properties.getProjectId());
            }

            return FirebaseApp.initializeApp(builder.build());
        }
        return FirebaseApp.getInstance();
    }

    @Bean
    public Firestore firestore(FirebaseApp firebaseApp) {
        return FirestoreClient.getFirestore(firebaseApp);
    }
}
