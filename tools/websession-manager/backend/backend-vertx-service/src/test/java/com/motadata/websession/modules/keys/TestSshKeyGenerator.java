package com.motadata.websession.modules.keys;

import com.motadata.websession.modules.keys.util.SshKeyGenerator;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestSshKeyGenerator
{

    @Test
    void testGenerateEd25519Success() throws Exception
    {
        String comment = "dev@websession";

        JsonObject keyObj = SshKeyGenerator.generateEd25519(comment);

        Assertions.assertNotNull(keyObj);

        Assertions.assertEquals("ed25519", keyObj.getString("type"));

        String pub = keyObj.getString("public.key");

        Assertions.assertNotNull(pub);

        Assertions.assertTrue(pub.startsWith("ssh-ed25519 "));

        Assertions.assertTrue(pub.endsWith(" " + comment));

        String fp = keyObj.getString("fingerprint");

        Assertions.assertNotNull(fp);

        Assertions.assertTrue(fp.startsWith("SHA256:"));

        String priv = keyObj.getString("private.key");

        Assertions.assertNotNull(priv);

        Assertions.assertTrue(priv.startsWith("-----BEGIN PRIVATE KEY-----"));

        Assertions.assertTrue(priv.endsWith("-----END PRIVATE KEY-----\n"));
    }

    @Test
    void testGenerateRsaSuccess() throws Exception
    {
        String comment = "admin@server";

        JsonObject keyObj = SshKeyGenerator.generateRsa(2048, comment);

        Assertions.assertNotNull(keyObj);

        Assertions.assertEquals("rsa", keyObj.getString("type"));

        String pub = keyObj.getString("public.key");

        Assertions.assertNotNull(pub);

        Assertions.assertTrue(pub.startsWith("ssh-rsa "));

        Assertions.assertTrue(pub.endsWith(" " + comment));

        String fp = keyObj.getString("fingerprint");

        Assertions.assertNotNull(fp);

        Assertions.assertTrue(fp.startsWith("SHA256:"));

        String priv = keyObj.getString("private.key");

        Assertions.assertNotNull(priv);

        Assertions.assertTrue(priv.startsWith("-----BEGIN PRIVATE KEY-----"));

        Assertions.assertTrue(priv.endsWith("-----END PRIVATE KEY-----\n"));
    }
}
