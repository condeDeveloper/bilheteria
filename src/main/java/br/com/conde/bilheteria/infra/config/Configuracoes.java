package br.com.conde.bilheteria.infra.config;

import br.com.conde.bilheteria.infra.seguranca.AutenticacaoController;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import java.time.Clock;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(AutenticacaoController.Usuarios.class)
public class Configuracoes {

    /** Relógio injetável: os testes o substituem para simular expiração sem esperar. */
    @Bean
    Clock relogio() {
        return Clock.systemUTC();
    }

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
            .info(new Info().title("Bilheteria").version("v1")
                .description("Venda de ingressos com reserva de lugares sob concorrência, saga de compra com compensação, outbox para Kafka, "
                    + "consumidores idempotentes com retry e DLT, cache e lock distribuído no Redis."))
            .components(new Components().addSecuritySchemes("bearer",
                new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT").description("POST /api/auth/token")));
    }

    /** Cache de disponibilidade no Redis com TTL curto e valores em JSON. */
    @Bean
    @Profile("!test")
    RedisCacheManagerBuilderCustomizer cachesRedis() {
        return builder -> builder.withCacheConfiguration("disponibilidade", RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofSeconds(5))
            .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer())));
    }
}
