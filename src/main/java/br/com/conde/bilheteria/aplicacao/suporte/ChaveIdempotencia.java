package br.com.conde.bilheteria.aplicacao.suporte;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Chave enviada pelo cliente no cabeçalho Idempotency-Key, ligada à reserva que ela criou. */
@Entity
@Table(name = "chaves_idempotencia")
public class ChaveIdempotencia {

    @Id
    @Column(length = 100)
    private String chave;

    @Column(name = "cliente_id", nullable = false, length = 64)
    private String clienteId;

    @Column(name = "reserva_id", nullable = false)
    private UUID reservaId;

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    protected ChaveIdempotencia() {
    }

    public ChaveIdempotencia(String chave, String clienteId, UUID reservaId, Instant criadaEm) {
        this.chave = chave;
        this.clienteId = clienteId;
        this.reservaId = reservaId;
        this.criadaEm = criadaEm;
    }

    public String getChave() { return chave; }
    public String getClienteId() { return clienteId; }
    public UUID getReservaId() { return reservaId; }
    public Instant getCriadaEm() { return criadaEm; }
}
