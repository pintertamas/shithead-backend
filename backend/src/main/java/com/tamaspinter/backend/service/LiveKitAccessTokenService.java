package com.tamaspinter.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Mints LiveKit access tokens: an HS256 JWT signed with the API secret, carrying a {@code video} grant.
 *
 * <p>Settings come from the {@code LIVEKIT_URL}, {@code LIVEKIT_API_KEY} and {@code LIVEKIT_API_SECRET}
 * environment variables. They are optional: when any is empty {@link #isConfigured()} is false and the
 * caller must refuse to issue tokens. The secret and the issued tokens are never logged.
 */
@Slf4j
@Service
public class LiveKitAccessTokenService {

    static final Duration TOKEN_TTL = Duration.ofHours(2);

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final String url;
    private final String apiKey;
    private final String apiSecret;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public LiveKitAccessTokenService(
            @Value("${LIVEKIT_URL:}") String url,
            @Value("${LIVEKIT_API_KEY:}") String apiKey,
            @Value("${LIVEKIT_API_SECRET:}") String apiSecret,
            ObjectMapper mapper) {
        this(url, apiKey, apiSecret, mapper, Clock.systemUTC());
    }

    LiveKitAccessTokenService(String url, String apiKey, String apiSecret, ObjectMapper mapper, Clock clock) {
        this.url = url;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** True only when the URL, API key and API secret are all set. */
    public boolean isConfigured() {
        return isSet(url) && isSet(apiKey) && isSet(apiSecret);
    }

    /** The LiveKit server WebSocket URL that clients connect to. */
    public String serverUrl() {
        return url;
    }

    /**
     * Creates a token that lets {@code identity} join {@code room} with microphone publishing only.
     *
     * @throws IllegalStateException when the service is not configured
     */
    public String createToken(String room, String identity, String name) {
        if (!isConfigured()) {
            throw new IllegalStateException("LiveKit is not configured");
        }
        Map<String, Object> video = new LinkedHashMap<>();
        video.put("room", room);
        video.put("roomJoin", true);
        video.put("canPublish", true);
        video.put("canSubscribe", true);
        video.put("canPublishData", false);
        video.put("canPublishSources", List.of("microphone"));

        Map<String, Object> claims = new LinkedHashMap<>();
        Instant now = clock.instant();
        claims.put("nbf", now.getEpochSecond());
        claims.put("exp", now.plus(TOKEN_TTL).getEpochSecond());
        claims.put("iss", apiKey);
        claims.put("sub", identity);
        claims.put("name", name != null && !name.isBlank() ? name : identity);
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("video", video);

        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        String signingInput = encodeJson(header) + "." + encodeJson(claims);
        return signingInput + "." + sign(signingInput);
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return URL_ENCODER.encodeToString(mapper.writeValueAsBytes(value));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not encode LiveKit token segment", e);
        }
    }

    private String sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(apiSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            return URL_ENCODER.encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            // The message carries no key material; the cause is the JDK's own description.
            throw new IllegalStateException("Could not sign LiveKit token", e);
        }
    }

    private static boolean isSet(String value) {
        return value != null && !value.isBlank();
    }
}
