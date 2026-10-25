package com.motadata.websession.modules.keys.util;

import io.vertx.core.json.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;

public class SshKeyGenerator
{

    public static JsonObject generateEd25519(String comment) throws Exception
    {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("Ed25519");

        KeyPair keyPair = kpg.generateKeyPair();

        byte[] encodedX509 = keyPair.getPublic().getEncoded();

        byte[] rawPublicKey = Arrays.copyOfRange(encodedX509, 12, 44);

        ByteArrayOutputStream wireStream = new ByteArrayOutputStream();

        writeString(wireStream, "ssh-ed25519");

        writeBytes(wireStream, rawPublicKey);

        byte[] wireBytes = wireStream.toByteArray();

        String commentStr = comment != null && !comment.trim().isEmpty() ? comment.trim() : "user@websession";

        String publicKey = "ssh-ed25519 " + Base64.getEncoder().encodeToString(wireBytes) + " " + commentStr;

        String fingerprint = computeSha256Fingerprint(wireBytes);

        String privateKeyPem = formatPkcs8Pem(keyPair.getPrivate().getEncoded());

        return new JsonObject()

            .put("type", "ed25519")

            .put("publicKey", publicKey)

            .put("fingerprint", fingerprint)

            .put("privateKey", privateKeyPem);
    }

    public static JsonObject generateRsa(int keySize, String comment) throws Exception
    {
        int validKeySize = keySize >= 2048 ? keySize : 3072;

        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");

        kpg.initialize(validKeySize);

        KeyPair keyPair = kpg.generateKeyPair();

        RSAPublicKey rsaPublic = (RSAPublicKey) keyPair.getPublic();

        ByteArrayOutputStream wireStream = new ByteArrayOutputStream();

        writeString(wireStream, "ssh-rsa");

        writeBytes(wireStream, rsaPublic.getPublicExponent().toByteArray());

        writeBytes(wireStream, rsaPublic.getModulus().toByteArray());

        byte[] wireBytes = wireStream.toByteArray();

        String commentStr = comment != null && !comment.trim().isEmpty() ? comment.trim() : "user@websession";

        String publicKey = "ssh-rsa " + Base64.getEncoder().encodeToString(wireBytes) + " " + commentStr;

        String fingerprint = computeSha256Fingerprint(wireBytes);

        String privateKeyPem = formatPkcs8Pem(keyPair.getPrivate().getEncoded());

        return new JsonObject()

            .put("type", "rsa")

            .put("keySize", validKeySize)

            .put("publicKey", publicKey)

            .put("fingerprint", fingerprint)

            .put("privateKey", privateKeyPem);
    }

    private static String computeSha256Fingerprint(byte[] wireBytes) throws Exception
    {
        MessageDigest sha256 = MessageDigest.getInstance("SHA-256");

        byte[] digest = sha256.digest(wireBytes);

        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
    }

    private static String formatPkcs8Pem(byte[] encodedPrivateKey)
    {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encodedPrivateKey);

        return "-----BEGIN PRIVATE KEY-----\n" + base64 + "\n-----END PRIVATE KEY-----\n";
    }

    private static void writeString(ByteArrayOutputStream out, String s) throws IOException
    {
        writeBytes(out, s.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(ByteArrayOutputStream out, byte[] b) throws IOException
    {
        out.write((b.length >>> 24) & 0xFF);

        out.write((b.length >>> 16) & 0xFF);

        out.write((b.length >>> 8) & 0xFF);

        out.write(b.length & 0xFF);

        out.write(b);
    }
}
