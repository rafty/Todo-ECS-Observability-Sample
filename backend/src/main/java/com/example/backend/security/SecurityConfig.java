package com.example.backend.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtAuthenticationConverter jwtAuthenticationConverter) throws Exception {
        // なぜ必要か: APIをステートレス構成に固定し、セッション依存の認証混入を防ぐため。
        http.csrf(AbstractHttpConfigurer::disable);
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // なぜ必要か: ALBヘルスチェックのみ匿名許可し、業務APIはJWT必須で保護するため。
        http.authorizeHttpRequests(authz -> authz
                .requestMatchers("/actuator/health").permitAll()
                .requestMatchers("/api/**").authenticated()
                .anyRequest().denyAll());

        // なぜ必要か: Cognito発行JWTをResource Serverで検証し、principalを `sub` に統一するため。
        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter)));
        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(Environment environment, AccessTokenClaimValidator accessTokenClaimValidator) {
        // なぜ必要か: テスト/ローカルでも必ず issuer を解決し、プレースホルダ未解決での起動失敗を防ぐため。
        final String issuerUri = environment.getProperty(
                "spring.security.oauth2.resourceserver.jwt.issuer-uri",
                "https://cognito-idp.ap-northeast-1.amazonaws.com/dummy-user-pool-id"
        );
        // なぜ必要か: issuerからJWKSエンドポイントを導出し、環境ごとに検証鍵を切り替え可能にするため。
        final String jwkSetUri = issuerUri.endsWith("/")
                ? issuerUri + ".well-known/jwks.json"
                : issuerUri + "/.well-known/jwks.json";
        final NimbusJwtDecoder jwtDecoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();

        // なぜ必要か: 標準検証（署名/時刻/issuer）に加えて token_use=access を必須化するため。
        final OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri),
                accessTokenClaimValidator
        );
        jwtDecoder.setJwtValidator(validator);
        return jwtDecoder;
    }

    @Bean
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        // なぜ必要か: scopeクレームを既存Spring Security互換の権限へ変換し、将来の認可拡張に備えるため。
        final JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        final JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authoritiesConverter.setAuthorityPrefix("SCOPE_");
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);

        // なぜ必要か: owner_subjectの解決元をJWT `sub` と明示し、アプリ全体の所有者識別を統一するため。
        authenticationConverter.setPrincipalClaimName(JwtClaimNames.SUB);
        return authenticationConverter;
    }
}
