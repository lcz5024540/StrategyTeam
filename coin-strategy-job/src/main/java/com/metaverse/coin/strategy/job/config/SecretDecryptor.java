package com.metaverse.coin.strategy.job.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.security.PrivateKey;
import java.security.PublicKey;

/**
 * 应用层非对称加密解密工具。
 *
 * <p>算法：RSA-OAEP-SHA256（RSA/ECB/OAEPPadding，OAEP 哈希和 MGF1 均使用 SHA-256）。
 *
 * <p>约定：所有通过环境变量注入的敏感值（DB 密码、Polymarket 私钥等），
 * 若需要加密存储，则以 {@code ENC(<base64>)} 格式包裹密文。
 * 不以 {@code ENC(} 开头的值视为明文直接返回，向后兼容。
 *
 * <p>密钥管理：
 * <ul>
 *   <li>RSA-2048 私钥（PKCS#8 PEM 格式）路径由环境变量 {@code APP_RSA_PRIVATE_KEY_PATH} 指定。</li>
 *   <li>公钥在启动时从私钥派生，用于 encrypt 接口。</li>
 *   <li>私钥文件权限应设置为 600，仅服务账号可读。</li>
 *   <li>使用 {@code scripts/generate-rsa-keys.sh} 生成密钥对。</li>
 * </ul>
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
            log.error("[SecretDecryptor] APP_RSA_PRIVATE_KEY_PATH not set; " +
                     "ENC(...) values will cause startup failure if present.");
        }
    }

    /**
     * 若 value 以 {@code ENC(...)} 包裹则用 RSA 私钥解密，否则原值返回。
     */
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

    /**
     * 用 RSA 公钥加密明文，返回 {@code ENC(...)} 格式字符串。
     * 用于本地工具生成待写入 env 文件的密文。
     */
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
