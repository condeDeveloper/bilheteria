package br.com.conde.bilheteria.infra.seguranca;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

/** API stateless com JWT assinado por chave simétrica (HS256). Papéis: ORGANIZADOR e CLIENTE. */
@Configuration
@EnableWebSecurity
public class SegurancaConfig {

    private final SecretKeySpec chave;

    public SegurancaConfig(@Value("${bilheteria.jwt.chave}") String chave) {
        if (chave == null || chave.length() < 32) throw new IllegalStateException("bilheteria.jwt.chave precisa ter ao menos 32 caracteres");
        this.chave = new SecretKeySpec(chave.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    SecurityFilterChain filtros(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**", "/simulador/**", "/docs/**", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .requestMatchers("/actuator/health/**", "/actuator/prometheus", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/eventos/**").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/eventos/**").hasRole("ORGANIZADOR")
                .requestMatchers("/api/ingressos/*/check-in").hasRole("ORGANIZADOR")
                .requestMatchers("/actuator/**").hasRole("ORGANIZADOR")
                .anyRequest().authenticated())
            .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder()).jwtAuthenticationConverter(conversor())))
            .exceptionHandling(Customizer.withDefaults())
            .build();
    }

    @Bean
    JwtDecoder decoder() {
        return NimbusJwtDecoder.withSecretKey(chave).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder encoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(chave));
    }

    private static JwtAuthenticationConverter conversor() {
        var autoridades = new JwtGrantedAuthoritiesConverter();
        autoridades.setAuthoritiesClaimName("papeis");
        autoridades.setAuthorityPrefix("ROLE_");
        var conversor = new JwtAuthenticationConverter();
        conversor.setJwtGrantedAuthoritiesConverter(autoridades);
        return conversor;
    }
}
