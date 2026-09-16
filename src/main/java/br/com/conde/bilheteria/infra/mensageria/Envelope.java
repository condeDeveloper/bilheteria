package br.com.conde.bilheteria.infra.mensageria;

import java.time.Instant;
import java.util.UUID;

/** Formato da mensagem no Kafka: identidade, tipo e o evento serializado como string JSON. */
public record Envelope(UUID id, String tipo, UUID agregadoId, Instant publicadoEm, String dados) {
}
