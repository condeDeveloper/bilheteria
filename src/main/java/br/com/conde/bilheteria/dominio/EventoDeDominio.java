package br.com.conde.bilheteria.dominio;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Fatos que aconteceram nos agregados. Interface selada: o switch nos consumidores é exaustivo e o compilador
 * avisa quando um novo evento aparece sem tratamento.
 */
public sealed interface EventoDeDominio {

    UUID agregadoId();

    Instant ocorridoEm();

    /** Nome estável usado como tipo da mensagem no Kafka. */
    default String tipo() {
        return getClass().getSimpleName();
    }

    record ReservaCriada(UUID agregadoId, UUID eventoId, String clienteId, List<UUID> lugares, BigDecimal valorTotal,
                         String tokenPagamento, Instant expiraEm, Instant ocorridoEm) implements EventoDeDominio {
    }

    record ReservaConfirmada(UUID agregadoId, UUID eventoId, String clienteId, List<UUID> lugares, BigDecimal valorTotal,
                             Instant ocorridoEm) implements EventoDeDominio {
    }

    record ReservaCancelada(UUID agregadoId, UUID eventoId, String motivo, Instant ocorridoEm) implements EventoDeDominio {
    }

    record ReservaExpirada(UUID agregadoId, UUID eventoId, Instant ocorridoEm) implements EventoDeDominio {
    }

    record IngressosEmitidos(UUID agregadoId, int quantidade, Instant ocorridoEm) implements EventoDeDominio {
    }

    record IngressoUtilizado(UUID agregadoId, UUID ingressoId, Instant ocorridoEm) implements EventoDeDominio {
    }
}
