package com.redhat.kb.testing;

import java.time.Instant;
import java.util.Base64;

/**
 * Builds the unsigned JWTs the tests hand to the auth client.
 *
 * <p>Shared because two test classes grew the same builder independently. It is only ever
 * a stand-in for a real token: nothing here signs anything, which is exactly why it suits
 * a client that reads {@code exp} without verifying the signature.
 */
public final class TestJwt {

    private TestJwt() {
        // Utility class
    }

    /**
     * Builds an unsigned JWT that expires in the given number of seconds.
     *
     * <p>The auth client returns such a token directly, with no SSO call.
     */
    public static String unsigned(String subject, long expiresInSeconds) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        String header = encoder.encodeToString("{\"alg\":\"none\"}".getBytes());
        String payload = encoder.encodeToString(
                ("{\"sub\":\"" + subject + "\",\"exp\":"
                        + (Instant.now().getEpochSecond() + expiresInSeconds) + "}").getBytes());
        return header + "." + payload + ".signature";
    }
}
