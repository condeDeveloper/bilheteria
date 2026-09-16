package br.com.conde.bilheteria.dominio;

import br.com.conde.bilheteria.dominio.Excecoes.ConflitoException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.util.UUID;

/**
 * Um assento numerado. É a unidade de disputa: dois clientes tentando o mesmo lugar ao mesmo tempo são
 * serializados pelo lock pessimista na leitura, e a versão protege contra escrita perdida fora dele.
 */
@Entity
@Table(name = "lugares")
public class Lugar {

    public enum Status { LIVRE, RESERVADO, VENDIDO }

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evento_id")
    private Evento evento;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "setor_id")
    private Setor setor;

    @Column(nullable = false, length = 20)
    private String codigo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column(name = "reserva_id")
    private UUID reservaId;

    @Version
    private long versao;

    protected Lugar() {
    }

    static Lugar criar(Evento evento, Setor setor, String codigo) {
        var l = new Lugar();
        l.id = UUID.randomUUID();
        l.evento = evento;
        l.setor = setor;
        l.codigo = codigo;
        l.status = Status.LIVRE;
        return l;
    }

    public void reservar(UUID reservaId) {
        if (status != Status.LIVRE) throw new ConflitoException("lugar " + codigo + " não está livre");
        status = Status.RESERVADO;
        this.reservaId = reservaId;
    }

    public void vender(UUID reservaId) {
        if (status != Status.RESERVADO || !reservaId.equals(this.reservaId)) throw new ConflitoException("lugar " + codigo + " não está reservado para esta reserva");
        status = Status.VENDIDO;
    }

    /** Compensação: devolve o lugar ao estoque se ainda pertence à reserva informada. Idempotente. */
    public void liberar(UUID reservaId) {
        if (reservaId.equals(this.reservaId) && status != Status.VENDIDO) {
            status = Status.LIVRE;
            this.reservaId = null;
        }
    }

    public boolean livre() {
        return status == Status.LIVRE;
    }

    public UUID getId() { return id; }
    public String getCodigo() { return codigo; }
    public Status getStatus() { return status; }
    public UUID getReservaId() { return reservaId; }
    public Setor getSetor() { return setor; }
    public Evento getEvento() { return evento; }
    public long getVersao() { return versao; }
}
