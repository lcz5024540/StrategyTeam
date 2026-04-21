package com.metaverse.coin.strategy.job.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

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

    private static final String ENC_PREFIX = "ENC(";
    private static final String ENC_SUFFIX = ")";

    /** RSA/ECB/OAEPPadding + SHA-256（哈希与 MGF1 均使用 SHA-256）*/
    private static final String CIPHER_TRANSFORMATION = "RSA/ECB/OAEPPadding";
    private static final OAEPParameterSpec OAEP_PARAMS = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    public SecretDecryptor(@Value("${APP_RSA_PRIVATE_KEY_PATH:}") String privateKeyPath) {
        if (StringUtils.hasText(privateKeyPath)) {
            try {
                String pem = Files.readString(Paths.get(privateKeyPath.trim()));
                // 去除 PEM 头尾与空白，得到纯 Base64 内容
                String base64 = pem
                        .replaceAll("-----BEGIN[^-]*-----", "")
                        .replaceAll("-----END[^-]*-----", "")
                        .replaceAll("\\s+", "");
                byte[] keyBytes = Base64.getDecoder().decode(base64);

                KeyFactory kf = KeyFactory.getInstance("RSA");
                this.privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

                // RSA CRT 私钥包含公钥指数，可直接派生公钥
                RSAPrivateCrtKey crtKey = (RSAPrivateCrtKey) this.privateKey;
                this.publicKey = kf.generatePublic(
                        new RSAPublicKeySpec(crtKey.getModulus(), crtKey.getPublicExponent()));

                log.info("[SecretDecryptor] RSA-2048 private key loaded: {}", privateKeyPath);
            } catch (Exception e) {
                throw new IllegalStateException(
                        "Failed to load RSA private key from: " + privateKeyPath, e);
            }
        } else {
            this.privateKey = null;
            this.publicKey = null;
            log.info("[SecretDecryptor] APP_RSA_PRIVATE_KEY_PATH not set; " +
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
        try {
            String payload = value.substring(ENC_PREFIX.length(), value.length() - ENC_SUFFIX.length());
            byte[] ciphertext = Base64.getDecoder().decode(payload);
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, privateKey, OAEP_PARAMS);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "RSA decryption failed. Ensure APP_RSA_PRIVATE_KEY_PATH matches the key " +
                    "used during encryption.", e);
        }
    }

    /**
     * 用 RSA 公钥加密明文，返回 {@code ENC(...)} 格式字符串。
     * 用于通过 /admin/encrypt-secret 接口生成待写入 env 文件的密文。
     */
    public String encrypt(String plaintext) {
        if (publicKey == null) {
            throw new IllegalStateException(
                    "Cannot encrypt: APP_RSA_PRIVATE_KEY_PATH is not set.");
        }
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, OAEP_PARAMS);
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ENC_PREFIX + Base64.getEncoder().encodeToString(ciphertext) + ENC_SUFFIX;
        } catch (Exception e) {
            throw new IllegalStateException("RSA encryption failed.", e);
        }
    }

    public boolean isEncrypted(String value) {
        return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
    }
}
