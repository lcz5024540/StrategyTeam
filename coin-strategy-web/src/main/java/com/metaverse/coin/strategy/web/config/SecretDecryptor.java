package com.metaverse.coin.strategy.web.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * 应用层非对称加密解密工具。
 *
 * <p>约定：环境变量中的敏感值若以 {@code ENC(<base64>)} 存储，则使用
 * {@code APP_RSA_PRIVATE_KEY_PATH} 指向的 RSA 私钥解密；非 ENC 值按明文兼容。
 */
@Slf4j
@Component
public class SecretDecryptor {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public SecretDecryptor(@Value("${APP_RSA_PRIVATE_KEY_PATH:}") String privateKeyPath) {
        if (StringUtils.hasText(privateKeyPath)) {
            this.privateKey = SecretCrypto.loadPrivateKey(privateKeyPath);
            this.publicKey = SecretCrypto.derivePublicKey(this.privateKey);
            log.info("[SecretDecryptor] RSA-2048 private key loaded: {}", privateKeyPath);
        } else {
            this.privateKey = null;
            this.publicKey = null;
            log.info("[SecretDecryptor] APP_RSA_PRIVATE_KEY_PATH not set; " +
                     "ENC(...) values will cause startup failure if present.");
        }
    }

    public String decryptIfEncrypted(String value) {
        if (!isEncrypted(value)) {
            return value;
        }
        if (privateKey == null) {
            throw new IllegalStateException(
                    "Secret value is encrypted ENC(...) but APP_RSA_PRIVATE_KEY_PATH is not set.");
        }
        return SecretCrypto.decrypt(value, privateKey);
    }

    public String encrypt(String plaintext) {
        if (publicKey == null) {
            throw new IllegalStateException(
                    "Cannot encrypt: APP_RSA_PRIVATE_KEY_PATH is not set.");
        }
        return SecretCrypto.encrypt(plaintext, publicKey);
    }

    public boolean isEncrypted(String value) {
        return SecretCrypto.isEncrypted(value);
    }
}
