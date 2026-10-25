package com.motadata.websession.modules.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

public class PasswordHasher
{

    private static final int SALT_BYTES = 16;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String hash(String password)
    {
        byte[] salt = new byte[SALT_BYTES];

        RANDOM.nextBytes(salt);

        byte[] digest = computeSha256(salt, password);

        String saltHex = HexFormat.of().formatHex(salt);

        String digestHex = HexFormat.of().formatHex(digest);



        return saltHex + ":" + digestHex;
    }

    public static boolean verify(String password, String storedHash)
    {
        if (password == null || storedHash == null)
        {
            return false;
        }

        String[] parts = storedHash.split(":");

        if (parts.length != 2)
        {
            return false;
        }

        try
        {
            byte[] salt = HexFormat.of().parseHex(parts[0]);

            byte[] expectedDigest = HexFormat.of().parseHex(parts[1]);

            byte[] actualDigest = computeSha256(salt, password);

            return MessageDigest.isEqual(actualDigest, expectedDigest);
        }
        catch (IllegalArgumentException e)
        {
            return false;
        }
    }

    private static byte[] computeSha256(byte[] salt, String password)
    {
        try
        {
            MessageDigest md = MessageDigest.getInstance("SHA-256");

            md.update(salt);

            return md.digest(password.getBytes(StandardCharsets.UTF_8));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }
}
