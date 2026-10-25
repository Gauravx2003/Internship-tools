package com.motadata.websession.modules.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class AesGcmEncryption
{

    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private static final int IV_LENGTH_BYTE = 12;

    private static final int TAG_LENGTH_BIT = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String encrypt(String plaintext, String masterKey)
    {
        if (plaintext == null)
        {
            return null;
        }

        try
        {
            byte[] iv = new byte[IV_LENGTH_BYTE];

            RANDOM.nextBytes(iv);

            SecretKey key = deriveKey(masterKey);

            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BIT, iv));

            byte[] cipherText = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            ByteBuffer byteBuffer = ByteBuffer.allocate(iv.length + cipherText.length);

            byteBuffer.put(iv);

            byteBuffer.put(cipherText);

            return Base64.getEncoder().encodeToString(byteBuffer.array());
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to encrypt secret", e);
        }
    }

    public static String decrypt(String encryptedBase64, String masterKey)
    {
        if (encryptedBase64 == null)
        {
            return null;
        }

        try
        {
            byte[] decoded = Base64.getDecoder().decode(encryptedBase64);

            ByteBuffer byteBuffer = ByteBuffer.wrap(decoded);

            byte[] iv = new byte[IV_LENGTH_BYTE];

            byteBuffer.get(iv);

            byte[] cipherText = new byte[byteBuffer.remaining()];

            byteBuffer.get(cipherText);

            SecretKey key = deriveKey(masterKey);

            Cipher cipher = Cipher.getInstance(ALGORITHM);

            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BIT, iv));

            byte[] plainText = cipher.doFinal(cipherText);

            return new String(plainText, StandardCharsets.UTF_8);
        }
        catch (Exception e)
        {
            throw new RuntimeException("Failed to decrypt secret", e);
        }
    }

    private static SecretKey deriveKey(String masterKey) throws Exception
    {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

        byte[] keyBytes = sha256.digest(masterKey.getBytes(StandardCharsets.UTF_8));

        return new SecretKeySpec(keyBytes, "AES");
    }
}
