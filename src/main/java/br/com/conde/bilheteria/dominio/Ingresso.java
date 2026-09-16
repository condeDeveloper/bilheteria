package br.com.conde.bilheteria.dominio;

import br.com.conde.bilheteria.dominio.Excecoes.RegraDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Ingresso emitido para um lugar de uma reserva confirmada. O código é assinado e vai no QR code. */
@Entity
@Table(name = "ingressos")
public class Ingresso {

    public enum Status { EMITIDO, UTILIZADO }

    @Id
    private UUID id;

    @Column(name = "reserva_id", nullable = false)
    private UUID reservaId;

    @Column(name = "lugar_id", nullable = false)
    private UUID lugarId;

    @Column(nullable = false, length = 200, unique = true)
    private String codigo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column(name = "emitido_em", nullable = false)
    private Instant emitidoEm;

    @Column(name = "utilizado_em")
    private Instant utilizadoEm;

    protected Ingresso() {
    }

    public static Ingresso emitir(UUID reservaId, UUID lugarId, String codigo, Instant agora) {
        var i = new Ingresso();
        i.id = UUID.randomUUID();
        i.reservaId = reservaId;
        i.lugarId = lugarId;
        i.codigo = codigo;
        i.status = Status.EMITIDO;
        i.emitidoEm = agora;
        return i;
    }

    /** Check-in na portaria. Um ingresso só entra uma vez. */
    public void utilizar(Instant agora) {
        if (status == Status.UTILIZADO) throw new RegraDeNegocioException("ingresso já utilizado em " + utilizadoEm);
        status = Status.UTILIZADO;
        utilizadoEm = agora;
    }

    public UUID getId() { return id; }
    public UUID getReservaId() { return reservaId; }
    public UUID getLugarId() { return lugarId; }
    public String getCodigo() { return codigo; }
    public Status getStatus() { return status; }
    public Instant getEmitidoEm() { return emitidoEm; }
    public Instant getUtilizadoEm() { return utilizadoEm; }
}
