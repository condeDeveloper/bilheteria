package br.com.conde.bilheteria.aplicacao.suporte;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

/** Inbox: registro de que um consumidor já tratou uma mensagem. Entrega duplicada do Kafka vira no-op. */
@Entity
@Table(name = "mensagens_processadas")
public class MensagemProcessada {

    @Embeddable
    public record Chave(@Column(name = "mensagem_id") UUID mensagemId, @Column(name = "consumidor", length = 60) String consumidor) implements Serializable {
    }

    @EmbeddedId
    private Chave chave;

    @Column(name = "processada_em", nullable = false)
    private Instant processadaEm;

    protected MensagemProcessada() {
    }

    public MensagemProcessada(UUID mensagemId, String consumidor, Instant processadaEm) {
        this.chave = new Chave(mensagemId, consumidor);
        this.processadaEm = processadaEm;
    }

    public Chave getChave() { return chave; }
    public Instant getProcessadaEm() { return processadaEm; }
}
