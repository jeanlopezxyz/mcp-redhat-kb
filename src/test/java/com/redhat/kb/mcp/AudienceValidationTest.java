package com.redhat.kb.mcp;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import io.quarkus.test.keycloak.server.KeycloakTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.restassured.RestAssured;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises audience validation (RFC 8707) against a real Keycloak.
 *
 * <p>Every other {@code @QuarkusTest} here runs with {@code quarkus.oidc.enabled=false},
 * which is what makes them fast and deterministic — but it also means the control the OWASP
 * table calls MCP07 was never actually run. The confused-deputy case it prevents needs a
 * genuine issuer: a token that is correctly signed by the right realm and still must be
 * refused, because it was minted for a different service. Nothing short of a real
 * authorization server produces that token.
 *
 * <p>The realm the lifecycle manager creates ({@code quarkus}, service client
 * {@code quarkus-service-app}, user {@code alice}) puts no audience mapper on any client,
 * so out of the box no token carries any service's audience — every token would be refused
 * and the test could not tell the audience check apart from a broken deployment. The setup
 * below therefore uses the Keycloak admin API to shape the realm into the two sides of the
 * confused-deputy scenario:
 *
 * <ul>
 *   <li>an audience mapper on {@code quarkus-service-app} stamping {@link #THIS_SERVER} —
 *       the audience this profile configures the server to require, and</li>
 *   <li>a second client, {@code other-service}, whose tokens are stamped with
 *       {@code other-api} instead.</li>
 * </ul>
 *
 * Both tokens come from the same realm, the same signing key and the same user; the only
 * difference between them is {@code aud}. Whatever separates their outcomes is the
 * audience check and nothing else — not the signature, not the issuer, not expiry.
 */
@QuarkusTest
@TestProfile(AudienceValidationTest.KeycloakProfile.class)
@QuarkusTestResource(KeycloakTestResourceLifecycleManager.class)
class AudienceValidationTest {

    /** The resource identifier (RFC 8707) this server requires in {@code aud}. */
    private static final String THIS_SERVER = "mcp-redhat-kb";

    private static final String REALM = "quarkus";

    /**
     * The lifecycle manager publishes the container's base address as the config property
     * {@code keycloak.url}. {@link KeycloakTestClient} appends
     * {@code /protocol/openid-connect/token} to whatever it is given, so it must receive
     * the realm URL, not the base URL — passing the base sends the grant to a path that
     * does not exist and the client quietly hands back a null token.
     */
    private static String realmUrl() {
        return ConfigProvider.getConfig().getValue("keycloak.url", String.class)
                + "/realms/" + REALM;
    }

    private final KeycloakTestClient keycloak = new KeycloakTestClient(realmUrl());

    /**
     * Provisions the audience mappers described in the class comment. Done here rather
     * than in a custom lifecycle manager because the realm, signing keys and OIDC
     * discovery are already in place — only token *contents* change, so the running
     * Quarkus instance needs no restart to observe it.
     */
    @BeforeAll
    static void stampAudiences() {
        String base = ConfigProvider.getConfig().getValue("keycloak.url", String.class);

        // The container bootstraps the admin/admin user; same credentials the lifecycle
        // manager itself uses to create the realm.
        String adminToken = RestAssured.given()
                .param("grant_type", "password")
                .param("username", "admin")
                .param("password", "admin")
                .param("client_id", "admin-cli")
                .post(base + "/realms/master/protocol/openid-connect/token")
                .then().statusCode(200)
                .extract().jsonPath().getString("access_token");

        // Make tokens minted for this server actually say so: aud += THIS_SERVER.
        String serviceClientUuid = RestAssured.given()
                .auth().oauth2(adminToken)
                .get(base + "/admin/realms/" + REALM + "/clients?clientId=quarkus-service-app")
                .then().statusCode(200)
                .extract().jsonPath().getString("[0].id");

        RestAssured.given()
                .auth().oauth2(adminToken)
                .contentType("application/json")
                .body(audienceMapper(THIS_SERVER))
                .post(base + "/admin/realms/" + REALM + "/clients/" + serviceClientUuid
                        + "/protocol-mappers/models")
                .then().statusCode(201);

        // The confused-deputy counterpart. The default web-app client cannot play this
        // role: it has no direct-access grants, so no token can be minted from it in a
        // headless test. This client mirrors quarkus-service-app in every respect except
        // the audience it stamps.
        RestAssured.given()
                .auth().oauth2(adminToken)
                .contentType("application/json")
                .body("""
                        {"clientId": "other-service",
                         "publicClient": false,
                         "secret": "secret",
                         "directAccessGrantsEnabled": true,
                         "enabled": true,
                         "protocolMappers": [%s]}""".formatted(audienceMapper("other-api")))
                .post(base + "/admin/realms/" + REALM + "/clients")
                .then().statusCode(201);
    }

    private static String audienceMapper(String audience) {
        return """
                {"name": "aud-%s",
                 "protocol": "openid-connect",
                 "protocolMapper": "oidc-audience-mapper",
                 "config": {"included.custom.audience": "%s",
                            "access.token.claim": "true"}}""".formatted(audience, audience);
    }

    @Test
    @DisplayName("accepts a token minted for this server")
    void acceptsTokenForThisAudience() {
        int status = callMcp(keycloak.getAccessToken("alice", "alice", "quarkus-service-app", "secret"));

        // A full 200, not merely "not 401": anything less would also be satisfied by a
        // request that authenticated and then broke, which is not the property under test.
        assertThat(status)
                .describedAs("a token carrying this server's audience should authenticate")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("refuses a token minted for a different client in the same realm")
    void refusesTokenForAnotherAudience() {
        // Same realm, same key, same user, valid and unexpired — but its audience is
        // other-api, not this server. This is the confused-deputy token. Chosen over
        // merely misconfiguring quarkus.oidc.token.audience because this way the sibling
        // test proves the *same* server configuration accepts the right audience: the
        // rejection below can only be the audience check.
        String foreign = keycloak.getAccessToken("alice", "alice", "other-service", "secret");

        assertThat(callMcp(foreign))
                .describedAs("without the audience check any realm token would spend the "
                        + "Red Hat subscription (confused deputy)")
                .isEqualTo(401);
    }

    @Test
    @DisplayName("refuses an anonymous call")
    void refusesAnonymousCall() {
        assertThat(RestAssured.given()
                .contentType("application/json")
                .body(initializeRequest())
                .post("/mcp")
                .statusCode())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("points an unauthenticated caller at the authorization server")
    void advertisesProtectedResourceMetadata() {
        // RFC 9728: the discovery document must stay readable without a token, or a client
        // has no way to find out where to authenticate.
        RestAssured.given()
                .get("/.well-known/oauth-protected-resource")
                .then()
                .statusCode(200);
    }

    private int callMcp(String token) {
        return RestAssured.given()
                .auth().oauth2(token)
                .contentType("application/json")
                // Streamable HTTP requires the client to accept both; without it the MCP
                // handler answers 400 even to an authenticated caller.
                .header("Accept", "application/json, text/event-stream")
                .body(initializeRequest())
                .post("/mcp")
                .statusCode();
    }

    private static String initializeRequest() {
        return """
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{
                  "protocolVersion":"2026-07-28",
                  "capabilities":{},
                  "clientInfo":{"name":"audience-test","version":"1.0"}}}""";
    }

    public static class KeycloakProfile implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of(
                    "quarkus.oidc.enabled", "true",
                    // The audience this server accepts — stamped into service-client
                    // tokens by stampAudiences(). KeycloakTestResourceLifecycleManager
                    // points quarkus.oidc.auth-server-url at the realm it starts.
                    "quarkus.oidc.token.audience", THIS_SERVER,
                    "quarkus.oidc.client-id", THIS_SERVER,
                    "redhat.api.offline-token", "sha256~test-token-not-a-real-credential",
                    "quarkus.mcp.server.sse.root-path", "mcp");
        }

        @Override
        public boolean disableGlobalTestResources() {
            // The Keycloak lifecycle manager is declared on the class instead, so it starts
            // only for this test rather than for every @QuarkusTest in the suite.
            return false;
        }
    }
}
