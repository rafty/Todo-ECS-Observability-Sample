package com.example.backend.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

// なぜ必要か: CognitoのIDトークン誤受け入れを避け、APIではaccess tokenのみ処理する方針を強制するため。
@Component
public class AccessTokenClaimValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error INVALID_TOKEN_USE = new OAuth2Error(
            "invalid_token",
            "The token_use claim must be access.",
            null
    );

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
        final String tokenUse = jwt.getClaimAsString("token_use");
        if ("access".equals(tokenUse)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(INVALID_TOKEN_USE);
    }
}
