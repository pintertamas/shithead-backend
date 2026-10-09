package com.tamaspinter.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveKitAccessTokenServiceTest {

    private static final String KEY = "APIkeyTest";
    private static final String SECRET = "super-secret-value-for-tests-only-0123456789";
    private static final Instant NOW = Instant.parse("2026-10-09T20:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final LiveKitAccessTokenService service = new LiveKitAccessTokenService(
            "wss://example.livekit.cloud", KEY, SECRET, MAPPER,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void tokenHasThreeSegmentsWithHs256Header() throws Exception {
        String token = service.createToken("ABC123", "user-1", "Tomi");

        String[] parts = token.split("\\.", -1);
        assertEquals(3, parts.length);
        JsonNode header = decode(parts[0]);
        assertEquals("HS256", header.get("alg").asText());
        assertEquals("JWT", header.get("typ").asText());
    }

    @Test
    void signatureVerifiesWithTheApiSecret() throws Exception {
        String token = service.createToken("ABC123", "user-1", "Tomi");
        String[] parts = token.split("\\.", -1);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, parts[2]);

        LiveKitAccessTokenService other = new LiveKitAccessTokenService(
                "wss://example.livekit.cloud", KEY, "a-different-secret", MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
        assertFalse(other.createToken("ABC123", "user-1", "Tomi").endsWith(parts[2]));
    }

    @Test
    void claimsCarryIssuerIdentityNameAndTimes() throws Exception {
        JsonNode claims = payload(service.createToken("ABC123", "user-1", "Tomi"));

        assertEquals(KEY, claims.get("iss").asText());
        assertEquals("user-1", claims.get("sub").asText());
        assertEquals("Tomi", claims.get("name").asText());
        assertEquals(NOW.getEpochSecond(), claims.get("nbf").asLong());
        assertEquals(NOW.plusSeconds(2 * 3600).getEpochSecond(), claims.get("exp").asLong());
        assertTrue(claims.get("jti").asText().length() > 0);
    }

    @Test
    void nameFallsBackToIdentityWhenBlank() throws Exception {
        JsonNode claims = payload(service.createToken("ABC123", "user-1", " "));

        assertEquals("user-1", claims.get("name").asText());
    }

    @Test
    void videoGrantAllowsOnlyMicrophoneInTheRequestedRoom() throws Exception {
        JsonNode video = payload(service.createToken("ABC123", "user-1", "Tomi")).get("video");

        assertEquals("ABC123", video.get("room").asText());
        assertTrue(video.get("roomJoin").asBoolean());
        assertTrue(video.get("canPublish").asBoolean());
        assertTrue(video.get("canSubscribe").asBoolean());
        assertFalse(video.get("canPublishData").asBoolean());
        assertEquals(1, video.get("canPublishSources").size());
        assertEquals("microphone", video.get("canPublishSources").get(0).asText());
    }

    @Test
    void tokensAreUniquePerCall() {
        assertFalse(service.createToken("ABC123", "user-1", "Tomi")
                .equals(service.createToken("ABC123", "user-1", "Tomi")));
    }

    @Test
    void notConfiguredWhenAnySettingIsEmpty() {
        assertTrue(service.isConfigured());
        assertFalse(new LiveKitAccessTokenService("", KEY, SECRET, MAPPER, Clock.systemUTC()).isConfigured());
        assertFalse(new LiveKitAccessTokenService("wss://x", "", SECRET, MAPPER, Clock.systemUTC()).isConfigured());
        assertFalse(new LiveKitAccessTokenService("wss://x", KEY, " ", MAPPER, Clock.systemUTC()).isConfigured());
    }

    @Test
    void createTokenRefusesWhenNotConfigured() {
        LiveKitAccessTokenService unset = new LiveKitAccessTokenService("", "", "", MAPPER, Clock.systemUTC());

        assertThrows(IllegalStateException.class, () -> unset.createToken("ABC123", "user-1", "Tomi"));
    }

    private static JsonNode decode(String segment) throws Exception {
        return MAPPER.readTree(Base64.getUrlDecoder().decode(segment));
    }

    private static JsonNode payload(String token) throws Exception {
        return decode(token.split("\\.", -1)[1]);
    }
}
