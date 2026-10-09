package com.tamaspinter.backend;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tamaspinter.backend.config.GameApiFunctionConfig;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;

/**
 * Lambda entry point of the game API ({@code handleRequest}).
 *
 * <p>It writes the router's response exactly as built. The generic Spring Cloud Function adapter
 * wraps non-typed results into a second API Gateway envelope (status 200, no CORS headers), which
 * browsers report as "Failed to fetch".
 */
public final class LambdaHandler implements RequestStreamHandler {

    private static final TypeReference<Map<String, Object>> EVENT_TYPE = new TypeReference<>() { };

    private final GameApiFunctionConfig api;
    private final ObjectMapper mapper;

    /** Used by Lambda: boots the Spring context once per execution environment (and for SnapStart). */
    public LambdaHandler() {
        this(bootContext());
    }

    private LambdaHandler(ConfigurableApplicationContext context) {
        this(context.getBean(GameApiFunctionConfig.class), context.getBean(ObjectMapper.class));
    }

    LambdaHandler(GameApiFunctionConfig api, ObjectMapper mapper) {
        this.api = api;
        this.mapper = mapper;
    }

    @Override
    public void handleRequest(InputStream input, OutputStream output, Context context) throws IOException {
        Map<String, Object> event = mapper.readValue(input, EVENT_TYPE);
        Object response = api.dispatch(event);
        mapper.writeValue(output, response);
    }

    private static ConfigurableApplicationContext bootContext() {
        return new SpringApplicationBuilder(BackendApplication.class)
                .web(WebApplicationType.NONE)
                .run();
    }
}
