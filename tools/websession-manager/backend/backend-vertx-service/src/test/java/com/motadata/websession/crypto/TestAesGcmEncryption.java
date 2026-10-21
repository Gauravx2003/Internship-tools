package com.motadata.websession.crypto;

import com.motadata.websession.modules.crypto.AesGcmEncryption;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestAesGcmEncryption
{

    private static final String MASTER_KEY = "test-secret-encryption-master-key!";

    @Test
    void testEncryptDecryptSuccess()
    {
        String plaintext = "mySuperSecretPassword@123";

        String encrypted = AesGcmEncryption.encrypt(plaintext, MASTER_KEY);

        Assertions.assertNotNull(encrypted);

        Assertions.assertNotEquals(plaintext, encrypted);

        String decrypted = AesGcmEncryption.decrypt(encrypted, MASTER_KEY);

        Assertions.assertEquals(plaintext, decrypted);
    }

    @Test
    void testUniqueCiphertextPerCall()
    {
        String plaintext = "samePassword";

        String enc1 = AesGcmEncryption.encrypt(plaintext, MASTER_KEY);

        String enc2 = AesGcmEncryption.encrypt(plaintext, MASTER_KEY);

        // Due to unique random IV, ciphertexts must differ
        Assertions.assertNotEquals(enc1, enc2);

        Assertions.assertEquals(plaintext, AesGcmEncryption.decrypt(enc1, MASTER_KEY));

        Assertions.assertEquals(plaintext, AesGcmEncryption.decrypt(enc2, MASTER_KEY));
    }

    @Test
    void testDecryptWithWrongKeyFails()
    {
        String plaintext = "secretData";

        String encrypted = AesGcmEncryption.encrypt(plaintext, MASTER_KEY);

        Assertions.assertThrows(RuntimeException.class, () ->
        {
            AesGcmEncryption.decrypt(encrypted, "wrong-master-key-here!");
        });
    }
}
