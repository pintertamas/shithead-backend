package com.tamaspinter.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

@RestController
@RequestMapping("/auth")
public class AuthController {

    @Value("${COGNITO_DOMAIN}")
    private String COGNITO_DOMAIN;

    @Value("${AWS_REGION}")
    private String AWS_REGION;

    @Value("${CLIENT_ID}")
    private String CLIENT_ID;

    // Must match the one configured in Cognito
    @Value("${CALLBACK_URL}")
    private String CALLBACK_URL;
    
    @GetMapping("/callback")
    public ResponseEntity<?> handleCallback(@RequestParam String code) {
        try {
            String tokenEndpoint = "https://" + COGNITO_DOMAIN + ".auth." + AWS_REGION + ".amazoncognito.com/oauth2/token";

            MultiValueMap<String, String> params = new LinkedMultiValueMap<>();
            params.add("grant_type", "authorization_code");
            params.add("client_id", CLIENT_ID);
            params.add("code", code);
            params.add("redirect_uri", CALLBACK_URL);

            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(params, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(tokenEndpoint, request, String.class);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(response.getBody());

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body("Failed to exchange code for tokens" + ": " + e.getMessage());
        }
    }
}