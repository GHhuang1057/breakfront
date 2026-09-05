package com.breakfront.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * 口令哈希（认证系统 M1）：PBKDF2-HMAC-SHA256 + 随机盐。
 *
 * <p>纯 JDK 实现（无第三方依赖），可用于 JUnit 单测。存储格式为单串
 * {@code <iterations>$<saltHex>$<hashHex>}，与具体持久化格式解耦。
 */
public final class PasswordHasher {

    /** 迭代次数：测试与正式同值（开销 ~40-80ms/次，可接受且抗暴力）。 */
    public static final int ITERATIONS = 120_000;
    public static final int SALT_BYTES = 16;
    public static final int KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private PasswordHasher() {
    }

    public static String randomSaltHex() {
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        return toHex(salt);
    }

    /** 计算口令哈希（新账号用：自行生成盐）。 */
    public static String hash(String rawPassword) {
        return hash(rawPassword, randomSaltHex());
    }

    /** 用给定盐计算口令哈希（校验用由存储串携带盐）。 */
    public static String hash(String rawPassword, String saltHex) {
        byte[] salt = fromHex(saltHex);
        PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] key = f.generateSecret(spec).getEncoded();
            return ITERATIONS + "$" + saltHex + "$" + toHex(key);
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }

    /** 校验口令是否与存储串匹配（常量时间比较）。 */
    public static boolean verify(String rawPassword, String stored) {
        String[] parts = stored.split("\\$");
        if (parts.length != 3) {
            return false;
        }
        try {
            int iters = Integer.parseInt(parts[0]);
            String saltHex = parts[1];
            byte[] expect = fromHex(parts[2]);
            PBEKeySpec spec = new PBEKeySpec(rawPassword.toCharArray(),
                    fromHex(saltHex), iters, expect.length * 8);
            SecretKeyFactory f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            byte[] actual = f.generateSecret(spec).getEncoded();
            return MessageDigest.isEqual(expect, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte v : b) {
            sb.append(Character.forDigit((v >> 4) & 0xF, 16));
            sb.append(Character.forDigit(v & 0xF, 16));
        }
        return sb.toString();
    }

    private static byte[] fromHex(String hex) {
        int n = hex.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
