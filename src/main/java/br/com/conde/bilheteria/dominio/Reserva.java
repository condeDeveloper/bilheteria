package br.com.conde.bilheteria.dominio;

import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaCancelada;
import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaConfirmada;
import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaCriada;
import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaExpirada;
import br.com.conde.bilheteria.dominio.Excecoes.RegraDeNegocioException;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Agregado da compra. Nasce PENDENTE segurando os lugares por um prazo; a saga a leva para CONFIRMADA (pagamento
 * aprovado) ou CANCELADA (recusado); o relógio a leva para EXPIRADA. Cada transição registra um evento.
 */
@Entity
@Table(name = "reservas")
public class Reserva {

    public enum Status { PENDENTE, CONFIRMADA, CANCELADA, EXPIRADA }

    public static final Duration PRAZO_PADRAO = Duration.ofMinutes(10);
    public static final int MAXIMO_DE_LUGARES = 8;

    @Id
    private UUID id;

    @Column(name = "evento_id", nullable = false)
    private UUID eventoId;

    @Column(name = "cliente_id", nullable = false, length = 64)
    private String clienteId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column(name = "valor_total", nullable = false, precision = 12, scale = 2)
    private BigDecimal valorTotal;

    @Column(name = "token_pagamento", nullable = false, length = 64)
    private String tokenPagamento;

    @Column(length = 200)
    private String motivo;

    @Column(name = "criada_em", nullable = false)
    private Instant criadaEm;

    @Column(name = "expira_em", nullable = false)
    private Instant expiraEm;

    @Column(name = "atualizada_em", nullable = false)
    private Instant atualizadaEm;

    @Version
    private long versao;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "reserva_lugares", joinColumns = @JoinColumn(name = "reserva_id"))
    @Column(name = "lugar_id", nullable = false)
    private List<UUID> lugares = new ArrayList<>();

    @Transient
    private final List<EventoDeDominio> eventos = new ArrayList<>();

    protected Reserva() {
    }

    public static Reserva criar(UUID eventoId, String clienteId, List<Lugar> lugares, String tokenPagamento, Instant agora) {
        if (clienteId == null || clienteId.isBlank()) throw new RegraDeNegocioException("cliente obrigatório");
        if (lugares == null || lugares.isEmpty()) throw new RegraDeNegocioException("escolha ao menos um lugar");
        if (lugares.size() > MAXIMO_DE_LUGARES) throw new RegraDeNegocioException("máximo de " + MAXIMO_DE_LUGARES + " lugares por reserva");
        if (tokenPagamento == null || tokenPagamento.isBlank()) throw new RegraDeNegocioException("token de pagamento obrigatório");
        var r = new Reserva();
        r.id = UUID.randomUUID();
        r.eventoId = eventoId;
        r.clienteId = clienteId.trim();
        r.status = Status.PENDENTE;
        r.tokenPagamento = tokenPagamento.trim();
        r.criadaEm = agora;
        r.atualizadaEm = agora;
        r.expiraEm = agora.plus(PRAZO_PADRAO);
        r.valorTotal = lugares.stream().map(l -> l.getSetor().getPreco()).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (var lugar : lugares) {
            lugar.reservar(r.id);
            r.lugares.add(lugar.getId());
        }
        r.eventos.add(new ReservaCriada(r.id, eventoId, r.clienteId, List.copyOf(r.lugares), r.valorTotal, r.tokenPagamento, r.expiraEm, agora));
        return r;
    }

    public void confirmar(List<Lugar> lugaresDaReserva, Instant agora) {
        exigir(Status.PENDENTE, "confirmar");
        if (agora.isAfter(expiraEm)) throw new RegraDeNegocioException("reserva expirada; pagamento não pode ser confirmado");
        lugaresDaReserva.forEach(l -> l.vender(id));
        status = Status.CONFIRMADA;
        atualizadaEm = agora;
        eventos.add(new ReservaConfirmada(id, eventoId, clienteId, List.copyOf(lugares), valorTotal, agora));
    }

    public void cancelar(String motivo, List<Lugar> lugaresDaReserva, Instant agora) {
        exigir(Status.PENDENTE, "cancelar");
        lugaresDaReserva.forEach(l -> l.liberar(id));
        status = Status.CANCELADA;
        this.motivo = motivo;
        atualizadaEm = agora;
        eventos.add(new ReservaCancelada(id, eventoId, motivo, agora));
    }

    public void expirar(List<Lugar> lugaresDaReserva, Instant agora) {
        exigir(Status.PENDENTE, "expirar");
        lugaresDaReserva.forEach(l -> l.liberar(id));
        status = Status.EXPIRADA;
        motivo = "prazo de pagamento esgotado";
        atualizadaEm = agora;
        eventos.add(new ReservaExpirada(id, eventoId, agora));
    }

    public boolean expirada(Instant agora) {
        return status == Status.PENDENTE && agora.isAfter(expiraEm);
    }

    private void exigir(Status esperado, String acao) {
        if (status != esperado) throw new RegraDeNegocioException("reserva " + status + " não pode " + acao);
    }

    public List<EventoDeDominio> drenarEventos() {
        var copia = List.copyOf(eventos);
        eventos.clear();
        return copia;
    }

    public UUID getId() { return id; }
    public UUID getEventoId() { return eventoId; }
    public String getClienteId() { return clienteId; }
    public Status getStatus() { return status; }
    public BigDecimal getValorTotal() { return valorTotal; }
    public String getTokenPagamento() { return tokenPagamento; }
    public String getMotivo() { return motivo; }
    public Instant getCriadaEm() { return criadaEm; }
    public Instant getExpiraEm() { return expiraEm; }
    public Instant getAtualizadaEm() { return atualizadaEm; }
    public long getVersao() { return versao; }
    public List<UUID> getLugares() { return List.copyOf(lugares); }
}
