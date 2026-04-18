package com.ogautam.kinkeeper.crypto;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.Base64;

@Slf4j
@Service
public class CryptoService {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final String keyBase64;
    private final SecureRandom random = new SecureRandom();
    private SecretKey key;
    private byte[] rawKey;

    public CryptoService(@Value("${KINKEEPER_ENCRYPTION_KEY:}") String keyBase64) {
        this.keyBase64 = keyBase64;
    }

    @PostConstruct
    void init() {
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalStateException("KINKEEPER_ENCRYPTION_KEY is not set");
        }
        byte[] keyBytes = Base64.getDecoder().decode(keyBase64);
        if (keyBytes.length != 32) {
            throw new IllegalStateException(
                    "KINKEEPER_ENCRYPTION_KEY must decode to 32 bytes (got " + keyBytes.length + ")");
        }
        this.rawKey = keyBytes;
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    public byte[] stateSigningKey() {
        return rawKey;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes("UTF-8"));

            ByteBuffer buf = ByteBuffer.allocate(iv.length + ciphertext.length);
            buf.put(iv);
            buf.put(ciphertext);
            return Base64.getEncoder().encodeToString(buf.array());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt", e);
        }
    }

    public String decrypt(String ciphertextBase64) {
        try {
            byte[] input = Base64.getDecoder().decode(ciphertextBase64);
            if (input.length <= IV_LENGTH_BYTES) {
                throw new IllegalArgumentException("Ciphertext too short");
            }

            byte[] iv = new byte[IV_LENGTH_BYTES];
            byte[] ciphertext = new byte[input.length - IV_LENGTH_BYTES];
            System.arraycopy(input, 0, iv, 0, IV_LENGTH_BYTES);
            System.arraycopy(input, IV_LENGTH_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ciphertext), "UTF-8");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt", e);
        }
    }
}
