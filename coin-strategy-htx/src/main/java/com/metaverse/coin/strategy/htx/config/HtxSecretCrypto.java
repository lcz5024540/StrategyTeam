package com.metaverse.coin.strategy.htx.config;

import javax.crypto.Cipher;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;

/** RSA helper for HTX account secrets stored as ENC(...). */
public final class HtxSecretCrypto {
    public static final String ENC_PREFIX = "ENC(";
    public static final String ENC_SUFFIX = ")";

    private static final String CIPHER_TRANSFORMATION = "RSA/ECB/OAEPPadding";
    private static final OAEPParameterSpec OAEP_PARAMS = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private HtxSecretCrypto() {
    }

    public static PrivateKey loadPrivateKey(String privateKeyPath) {
        try {
            String pem = Files.readString(Paths.get(privateKeyPath.trim()));
            String base64 = pem
                    .replaceAll("-----BEGIN[^-]*-----", "")
                    .replaceAll("-----END[^-]*-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load RSA private key from: " + privateKeyPath, e);
        }
    }

    public static PublicKey derivePublicKey(PrivateKey privateKey) {
        if (!(privateKey instanceof RSAPrivateCrtKey crtKey)) {
            throw new IllegalStateException("RSA private key must be a CRT key to derive public key.");
        }
        try {
            return KeyFactory.getInstance("RSA").generatePublic(
                    new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to derive RSA public key from private key.", e);
        }
    }

    public static String encrypt(String plaintext, PublicKey publicKey) {
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_PARAMS);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ENC_PREFIX + Base64.getEncoder().encodeToString(ciphertext) + ENC_SUFFIX;
        } catch (Exception e) {
            throw new IllegalStateException("RSA encryption failed.", e);
        }
    }

    public static String decrypt(String encryptedValue, PrivateKey privateKey) {
        if (!isEncrypted(encryptedValue)) {
            throw new IllegalArgumentException("Secret value must use ENC(...) format.");
        }
        try {
            String payload = encryptedValue.substring(ENC_PREFIX.length(), encryptedValue.length() - ENC_SUFFIX.length());
            byte[] ciphertext = Base64.getDecoder().decode(payload);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_PARAMS);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "RSA decryption failed. Ensure APP_RSA_PRIVATE_KEY_PATH matches the key used during encryption.", e);
        }
    }

    public static boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }
}
