package com.motadata.websession.crypto;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestPasswordHasher
{

    @Test
    void testHashAndVerifySuccess()
    {
        String password = "SecretPassword@123";

        String hash = PasswordHasher.hash(password);

        Assertions.assertNotNull(hash);

        Assertions.assertTrue(hash.contains(":"));

        boolean match = PasswordHasher.verify(password, hash);

        Assertions.assertTrue(match);
    }

    @Test
    void testVerifyWrongPassword()
    {
        String password = "CorrectPassword@123";

        String hash = PasswordHasher.hash(password);

        boolean match = PasswordHasher.verify("WrongPassword@999", hash);

        Assertions.assertFalse(match);
    }

    @Test
    void testUniqueSaltsForSamePassword()
    {
        String password = "SamePassword@123";

        String hash1 = PasswordHasher.hash(password);

        String hash2 = PasswordHasher.hash(password);

        Assertions.assertNotEquals(hash1, hash2);

        Assertions.assertTrue(PasswordHasher.verify(password, hash1));

        Assertions.assertTrue(PasswordHasher.verify(password, hash2));
    }
}
