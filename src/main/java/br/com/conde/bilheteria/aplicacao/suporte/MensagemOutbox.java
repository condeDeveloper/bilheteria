package br.com.conde.bilheteria.aplicacao.suporte;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Linha do outbox: um evento de domínio serializado esperando o relay publicá-lo no Kafka. */
@Entity
@Table(name = "outbox")
public class MensagemOutbox {

    @Id
    private UUID id;

    @Column(nullable = false, length = 60)
    private String tipo;

    @Column(name = "agregado_id", nullable = false)
    private UUID agregadoId;

    @Column(nullable = false, columnDefinition = "text")
    private String corpo;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @Column(name = "publicado_em")
    private Instant publicadoEm;

    @Column(nullable = false)
    private int tentativas;

    @Column(name = "ultimo_erro", length = 1000)
    private String ultimoErro;

    protected MensagemOutbox() {
    }

    public MensagemOutbox(UUID id, String tipo, UUID agregadoId, String corpo, Instant criadoEm) {
        this.id = id;
        this.tipo = tipo;
        this.agregadoId = agregadoId;
        this.corpo = corpo;
        this.criadoEm = criadoEm;
    }

    public void marcarPublicada(Instant quando) {
        publicadoEm = quando;
        ultimoErro = null;
    }

    public void registrarFalha(String erro) {
        tentativas++;
        ultimoErro = erro == null ? null : erro.substring(0, Math.min(1000, erro.length()));
    }

    public UUID getId() { return id; }
    public String getTipo() { return tipo; }
    public UUID getAgregadoId() { return agregadoId; }
    public String getCorpo() { return corpo; }
    public Instant getCriadoEm() { return criadoEm; }
    public Instant getPublicadoEm() { return publicadoEm; }
    public int getTentativas() { return tentativas; }
    public String getUltimoErro() { return ultimoErro; }
}
