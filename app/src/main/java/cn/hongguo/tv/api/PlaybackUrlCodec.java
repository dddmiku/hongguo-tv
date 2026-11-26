package cn.hongguo.tv.api;

import java.security.MessageDigest;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Official playback URL envelope decoder; mirrors the desktop provider contract. */
final class PlaybackUrlCodec {
    private static final byte[] SALT = hex(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58");
    private PlaybackUrlCodec() {}

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    /** Returns null for malformed or unsupported envelopes. */
    static String decode(String valueBase64, String seedBase64) {
        if (valueBase64 == null || seedBase64 == null) return null;
        try {
            byte[] cipher = Base64.getDecoder().decode(valueBase64.replaceAll("\\s+", ""));
            byte[] seed = Base64.getDecoder().decode(seedBase64.replaceAll("\\s+", ""));
            if (seed.length != 32 || cipher.length < 20 || (cipher.length - 4) % 16 != 0) return null;
            int magic = (cipher[0] & 0xff) | ((cipher[1] & 0xff) << 8);
            int version = (cipher[2] & 0xff) | ((cipher[3] & 0xff) << 8);
            if (magic != 0xA8 || version != 1) return null;
            MessageDigest sha512 = MessageDigest.getInstance("SHA-512");
            byte[] first = sha512.digest(seed);
            byte[] material = sha512.digest(concat(first, SALT));
            Cipher aes = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(material, 0, 16, "AES"),
                    new IvParameterSpec(material, 16, 16));
            String text = new String(aes.doFinal(cipher, 4, cipher.length - 4), "UTF-8");
            return text.isEmpty() ? null : text;
        } catch (Throwable error) {
            return null;
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] out = new byte[left.length + right.length];
        System.arraycopy(left, 0, out, 0, left.length);
        System.arraycopy(right, 0, out, left.length, right.length);
        return out;
    }
}
