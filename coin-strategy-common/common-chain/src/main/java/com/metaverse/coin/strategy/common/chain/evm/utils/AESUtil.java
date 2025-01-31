package com.metaverse.coin.strategy.common.chain.evm.utils;

import org.apache.commons.lang3.StringUtils;
import org.java_websocket.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.UnsupportedEncodingException;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.AlgorithmParameterSpec;

public class AESUtil {

	private static final String ENCRYPTION_KEY = "89abcdefRwcmlVpg";
	private static final String ENCRYPTION_IV = "4e5Wa71fYoT7MFEX";

	/**
	 * 加密
	 *
	 * @param src
	 * @return
	 */
	public static String encrypt(String src) {
		try {
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.ENCRYPT_MODE, makeKey(), makeIv());
			return Base64.encodeBytes(cipher.doFinal(src.getBytes()));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * 解密
	 * @param src
	 * @return
	 */
	public static String decrypt(String src) {
		String decrypted = "";
		if(StringUtils.isBlank(src)){
			return decrypted;
		}
		try {
			Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
			cipher.init(Cipher.DECRYPT_MODE, makeKey(), makeIv());
			decrypted = new String(cipher.doFinal(Base64.decode(src)));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		return decrypted;
	}

	static AlgorithmParameterSpec makeIv() {
		try {
			return new IvParameterSpec(ENCRYPTION_IV.getBytes("UTF-8"));
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		return null;
	}

	static Key makeKey() {
		try {
			MessageDigest md = MessageDigest.getInstance("SHA-256");
//			byte[] key = md.digest(ENCRYPTION_KEY.getBytes("UTF-8"));
			byte[] key = ENCRYPTION_KEY.getBytes("UTF-8");

			return new SecretKeySpec(key, "AES");
		} catch (NoSuchAlgorithmException e) {
			e.printStackTrace();
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}

		return null;
	}

	public static void main(String[] args) {

		String str = "hello";
		String s1 = encrypt(str);
		System.out.println("s1:" + s1);

		String s2 = decrypt(s1);
		System.out.println("s2:" + s2);


	}


}
