package com.metaverse.coin.strategy.htx.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.PrivateKey;
import java.security.PublicKey;

/** Decrypts HTX account secrets stored in DB as ENC(...). */
@Slf4j
@Component
public class HtxSecretDecryptor {
    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public HtxSecretDecryptor(@Value("${APP_RSA_PRIVATE_KEY_PATH:}") String privateKeyPath) {
        if (StringUtils.hasText(privateKeyPath)) {
            this.privateKey = HtxSecretCrypto.loadPrivateKey(privateKeyPath);
            this.publicKey = HtxSecretCrypto.derivePublicKey(this.privateKey);
            log.info("[HTX-SECRET] RSA private key loaded: {}", privateKeyPath);
        } else {
            this.privateKey = null;
            this.publicKey = null;
            log.info("[HTX-SECRET] APP_RSA_PRIVATE_KEY_PATH not set; ENC(...) HTX account secrets are unavailable.");
        }
    }

    public String decryptIfEncrypted(String value) {
        if (!isEncrypted(value)) {
            return value;
        }
        if (privateKey == null) {
            throw new IllegalStateException("HTX account secret is ENC(...) but APP_RSA_PRIVATE_KEY_PATH is not set.");
        }
        return HtxSecretCrypto.decrypt(value, privateKey);
    }

    public String encrypt(String plaintext) {
        if (publicKey == null) {
            throw new IllegalStateException("Cannot encrypt HTX account secret: APP_RSA_PRIVATE_KEY_PATH is not set.");
        }
        return HtxSecretCrypto.encrypt(plaintext, publicKey);
    }

    public boolean isEncrypted(String value) {
        return HtxSecretCrypto.isEncrypted(value);
    }
}
