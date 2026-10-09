package com.tamaspinter.backend.config;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.tamaspinter.backend.handler.VoiceTokenHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.function.Function;

/**
 * Handler bean for the voice chat token route, registered in {@link ApiRoutes}.
 */
@Configuration
@RequiredArgsConstructor
public class VoiceFunctionConfig {

    private final VoiceTokenHandler voiceTokens;

    @Bean
    public Function<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> voiceToken() {
        return voiceTokens::issueToken;
    }
}
