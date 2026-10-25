package wtm.web;

import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 单口令鉴权：PBKDF2 口令哈希 + HMAC 会话 Cookie。零外部依赖。 */
public final class Auth {

    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final SecureRandom RNG = new SecureRandom();

    private Auth() {}

    public static String hash(char[] password) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] derived = pbkdf2(password, salt, ITERATIONS, KEY_BITS);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        return "pbkdf2$sha256$" + ITERATIONS + "$" + b64.encodeToString(salt) + "$" + b64.encodeToString(derived);
    }

    public static boolean verify(char[] password, String stored) {
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 5 || !"pbkdf2".equals(parts[0]) || !"sha256".equals(parts[1])) {
                return false;
            }
            int iterations = Integer.parseInt(parts[2]);
            Base64.Decoder b64 = Base64.getUrlDecoder();
            byte[] salt = b64.decode(parts[3]);
            byte[] expected = b64.decode(parts[4]);
            byte[] actual = pbkdf2(password, salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyBits) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyBits);
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("PBKDF2 不可用", e);
        }
    }

    public static String hmacHex(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC 不可用", e);
        }
    }

    public static String randomToken(int bytes) {
        byte[] buf = new byte[bytes];
        RNG.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    /** 生成 "过期时间戳.签名" 形式的会话值。 */
    public static String issueSession(String secret, long ttlMillis) {
        long expiry = System.currentTimeMillis() + ttlMillis;
        String payload = String.valueOf(expiry);
        return payload + "." + hmacHex(secret, payload);
    }

    public static boolean validSession(String secret, String value) {
        if (value == null) {
            return false;
        }
        int dot = value.indexOf('.');
        if (dot <= 0) {
            return false;
        }
        String payload = value.substring(0, dot);
        String mac = value.substring(dot + 1);
        if (!MessageDigest.isEqual(mac.getBytes(StandardCharsets.UTF_8),
                hmacHex(secret, payload).getBytes(StandardCharsets.UTF_8))) {
            return false;
        }
        try {
            return Long.parseLong(payload) > System.currentTimeMillis();
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
