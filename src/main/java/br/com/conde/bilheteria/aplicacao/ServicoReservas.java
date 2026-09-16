package br.com.conde.bilheteria.aplicacao;

import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioChavesIdempotencia;
import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioEventos;
import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioLugares;
import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioReservas;
import br.com.conde.bilheteria.aplicacao.suporte.ChaveIdempotencia;
import br.com.conde.bilheteria.aplicacao.suporte.Outbox;
import br.com.conde.bilheteria.dominio.Excecoes.ConflitoException;
import br.com.conde.bilheteria.dominio.Excecoes.RecursoNaoEncontradoException;
import br.com.conde.bilheteria.dominio.Excecoes.RegraDeNegocioException;
import br.com.conde.bilheteria.dominio.Reserva;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Casos de uso da reserva. O núcleo é {@link #criar}: dentro de uma transação, trava os lugares pedidos com
 * SELECT ... FOR UPDATE, verifica que todos estão livres, cria a reserva e grava o evento no outbox. Dois clientes
 * disputando o mesmo lugar são serializados pelo banco; o segundo vê o lugar ocupado e recebe 409.
 */
@Service
public class ServicoReservas {

    public record NovaReserva(UUID eventoId, List<String> codigosLugares, String tokenPagamento) {
    }

    public record Criacao(Reserva reserva, boolean repetida) {
    }

    private final RepositorioReservas reservas;
    private final RepositorioLugares lugares;
    private final RepositorioEventos eventos;
    private final RepositorioChavesIdempotencia chaves;
    private final Outbox outbox;
    private final CacheManager caches;
    private final Clock relogio;

    public ServicoReservas(RepositorioReservas reservas, RepositorioLugares lugares, RepositorioEventos eventos,
                           RepositorioChavesIdempotencia chaves, Outbox outbox, CacheManager caches, Clock relogio) {
        this.reservas = reservas;
        this.lugares = lugares;
        this.eventos = eventos;
        this.chaves = chaves;
        this.outbox = outbox;
        this.caches = caches;
        this.relogio = relogio;
    }

    @Transactional(noRollbackFor = ConflitoException.class)
    public Criacao criar(String clienteId, String chaveIdempotencia, NovaReserva comando) {
        if (chaveIdempotencia != null) {
            var existente = chaves.findById(chaveIdempotencia);
            if (existente.isPresent()) {
                if (!existente.get().getClienteId().equals(clienteId)) throw new ConflitoException("chave de idempotência pertence a outro cliente");
                return new Criacao(obter(existente.get().getReservaId()), true);
            }
        }

        var evento = eventos.findById(comando.eventoId()).orElseThrow(() -> new RecursoNaoEncontradoException("evento", comando.eventoId()));
        if (!evento.vendasAbertas()) throw new RegraDeNegocioException("vendas não estão abertas para este evento");

        var codigos = new TreeSet<>(comando.codigosLugares() == null ? List.<String>of() : comando.codigosLugares());
        if (codigos.isEmpty()) throw new RegraDeNegocioException("escolha ao menos um lugar");
        var travados = lugares.bloquearPorCodigos(evento.getId(), codigos);
        if (travados.size() != codigos.size()) {
            var achados = travados.stream().map(l -> l.getCodigo()).toList();
            var faltando = codigos.stream().filter(c -> !achados.contains(c)).toList();
            throw new RecursoNaoEncontradoException("lugar", String.join(", ", faltando));
        }
        var ocupados = travados.stream().filter(l -> !l.livre()).map(l -> l.getCodigo()).toList();
        if (!ocupados.isEmpty()) throw new ConflitoException("lugares indisponíveis: " + String.join(", ", ocupados));

        var agora = Instant.now(relogio);
        var reserva = Reserva.criar(evento.getId(), clienteId, travados, comando.tokenPagamento(), agora);
        reservas.save(reserva);
        outbox.registrar(reserva.drenarEventos());
        if (chaveIdempotencia != null) {
            try {
                chaves.saveAndFlush(new ChaveIdempotencia(chaveIdempotencia, clienteId, reserva.getId(), agora));
            } catch (DataIntegrityViolationException e) {
                throw new ConflitoException("requisição com esta chave de idempotência já está em processamento");
            }
        }
        invalidarDisponibilidade(evento.getId());
        return new Criacao(reserva, false);
    }

    @Transactional
    public Reserva cancelar(UUID reservaId, String clienteId) {
        var reserva = obter(reservaId);
        if (!reserva.getClienteId().equals(clienteId)) throw new RecursoNaoEncontradoException("reserva", reservaId);
        reserva.cancelar("cancelada pelo cliente", lugares.bloquearPorIds(reserva.getLugares()), Instant.now(relogio));
        outbox.registrar(reserva.drenarEventos());
        invalidarDisponibilidade(reserva.getEventoId());
        return reserva;
    }

    /** Expira uma reserva pendente vencida, devolvendo os lugares. Chamado pelo agendador, uma transação por reserva. */
    @Transactional
    public boolean expirar(UUID reservaId) {
        var reserva = obter(reservaId);
        var agora = Instant.now(relogio);
        if (!reserva.expirada(agora)) return false;
        reserva.expirar(lugares.bloquearPorIds(reserva.getLugares()), agora);
        outbox.registrar(reserva.drenarEventos());
        invalidarDisponibilidade(reserva.getEventoId());
        return true;
    }

    @Transactional(readOnly = true)
    public List<UUID> pendentesVencidas(int limite) {
        return reservas.findByStatusAndExpiraEmBefore(Reserva.Status.PENDENTE, Instant.now(relogio), PageRequest.of(0, limite))
            .stream().map(Reserva::getId).toList();
    }

    @Transactional(readOnly = true)
    public Reserva obter(UUID reservaId) {
        return reservas.findById(reservaId).orElseThrow(() -> new RecursoNaoEncontradoException("reserva", reservaId));
    }

    @Transactional(readOnly = true)
    public Optional<Reserva> obterDoCliente(UUID reservaId, String clienteId) {
        return reservas.findById(reservaId).filter(r -> r.getClienteId().equals(clienteId));
    }

    @Transactional(readOnly = true)
    public List<Reserva> doCliente(String clienteId) {
        return reservas.findByClienteIdOrderByCriadaEmDesc(clienteId);
    }

    void invalidarDisponibilidade(UUID eventoId) {
        var cache = caches.getCache("disponibilidade");
        if (cache != null) cache.evict(eventoId);
    }
}
