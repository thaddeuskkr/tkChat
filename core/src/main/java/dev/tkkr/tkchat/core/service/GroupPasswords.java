package dev.tkkr.tkchat.core.service;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class GroupPasswords {
    private static final String SCHEME = "pbkdf2-sha256";
    private static final int ITERATIONS = 210_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private GroupPasswords() {
    }

    public static String hash(String password) {
        validate(password);
        byte[] salt = new byte[SALT_BYTES];
        RANDOM.nextBytes(salt);
        byte[] derived = derive(password.toCharArray(), salt, ITERATIONS, KEY_BITS);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return SCHEME + "$" + ITERATIONS + "$" + encoder.encodeToString(salt) + "$"
                + encoder.encodeToString(derived);
    }

    public static boolean matches(String password, String encoded) {
        if (password == null || encoded == null || encoded.isBlank()) {
            return false;
        }
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !SCHEME.equals(parts[0])) {
            return false;
        }
        try {
            int iterations = Integer.parseInt(parts[1]);
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] salt = decoder.decode(parts[2]);
            byte[] expected = decoder.decode(parts[3]);
            byte[] actual = derive(password.toCharArray(), salt, iterations, expected.length * 8);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException invalidEncoding) {
            return false;
        }
    }

    public static void validate(String password) {
        if (password == null || password.length() < 4 || password.length() > 64
                || password.chars().anyMatch(character -> Character.isWhitespace(character)
                || Character.isISOControl(character))) {
            throw new IllegalArgumentException("Passwords must be 4-64 characters without whitespace");
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations, int keyBits) {
        PBEKeySpec specification = new PBEKeySpec(password, salt, iterations, keyBits);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", unavailable);
        } finally {
            specification.clearPassword();
            java.util.Arrays.fill(password, '\0');
        }
    }
}
