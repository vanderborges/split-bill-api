package com.splitbill.infrastructure.push;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Credencial da conta de servico do Firebase (projeto split-bill-f135e).
 * Em producao vem inteira na variavel FIREBASE_CREDENTIALS_JSON (Render);
 * local, da pra apontar pro arquivo com FIREBASE_CREDENTIALS_FILE. Sem
 * nenhuma das duas, o push fica desligado e so a notificacao in-app vale.
 */
@Component
public class FirebaseMessagingProvider {

    private static final Logger log = LoggerFactory.getLogger(FirebaseMessagingProvider.class);

    private final FirebaseMessaging messaging;

    public FirebaseMessagingProvider(
            @Value("${split-bill.push.firebase-credentials-json:}") String credentialsJson,
            @Value("${split-bill.push.firebase-credentials-file:}") String credentialsFile
    ) {
        this.messaging = initialize(credentialsJson, credentialsFile);
    }

    public Optional<FirebaseMessaging> get() {
        return Optional.ofNullable(messaging);
    }

    private static FirebaseMessaging initialize(String json, String file) {
        try (InputStream credentials = open(json, file)) {
            if (credentials == null) {
                log.info("Push desligado: FIREBASE_CREDENTIALS_JSON/FIREBASE_CREDENTIALS_FILE nao configurado.");
                return null;
            }
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(credentials))
                    .build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty()
                    ? FirebaseApp.initializeApp(options)
                    : FirebaseApp.getInstance();
            log.info("Push ligado (Firebase Cloud Messaging).");
            return FirebaseMessaging.getInstance(app);
        } catch (IOException | RuntimeException error) {
            // Credencial invalida nao derruba a API: so fica sem push.
            log.error("Push desligado: credencial do Firebase invalida ({}).", error.getMessage());
            return null;
        }
    }

    private static InputStream open(String json, String file) throws IOException {
        if (json != null && !json.isBlank()) {
            return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        }
        if (file != null && !file.isBlank()) {
            return new FileInputStream(file);
        }
        return null;
    }
}
