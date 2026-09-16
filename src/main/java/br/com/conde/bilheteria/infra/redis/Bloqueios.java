package br.com.conde.bilheteria.infra.redis;

import br.com.conde.bilheteria.aplicacao.portas.Portas.PortaBloqueio;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class Bloqueios {

    private Bloqueios() {
    }

    /**
     * Lock distribuído no Redis: SET NX PX para adquirir e um script Lua para liberar só se o valor ainda for o nosso
     * (evita soltar o lock de outra instância depois que o nosso expirou).
     */
    @Component
    @Profile("!test")
    public static class BloqueioRedis implements PortaBloqueio {

        private static final DefaultRedisScript<Long> LIBERAR = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", Long.class);

        private final StringRedisTemplate redis;
        private final String dono = UUID.randomUUID().toString();

        public BloqueioRedis(StringRedisTemplate redis) {
            this.redis = redis;
        }

        @Override
        public boolean tentarAdquirir(String chave, Duration duracao) {
            return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent("bloqueio:" + chave, dono, duracao));
        }

        @Override
        public void liberar(String chave) {
            redis.execute(LIBERAR, List.of("bloqueio:" + chave), dono);
        }
    }

    /** Versão em memória para os testes locais e para rodar sem Redis: vale para uma instância só. */
    @Component
    @Profile("test")
    public static class BloqueioEmMemoria implements PortaBloqueio {

        private final Map<String, Instant> ocupados = new ConcurrentHashMap<>();

        @Override
        public boolean tentarAdquirir(String chave, Duration duracao) {
            var agora = Instant.now();
            var anterior = ocupados.compute(chave, (k, expira) -> expira == null || expira.isBefore(agora) ? agora.plus(duracao) : expira);
            return anterior.equals(agora.plus(duracao));
        }

        @Override
        public void liberar(String chave) {
            ocupados.remove(chave);
        }
    }
}
