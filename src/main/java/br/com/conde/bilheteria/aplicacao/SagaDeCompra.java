package br.com.conde.bilheteria.aplicacao;

import br.com.conde.bilheteria.aplicacao.portas.Portas.PortaPagamento;
import br.com.conde.bilheteria.aplicacao.portas.Portas.ResultadoPagamento;
import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioLugares;
import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioReservas;
import br.com.conde.bilheteria.aplicacao.suporte.Outbox;
import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaCriada;
import br.com.conde.bilheteria.dominio.Reserva;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Orquestra a compra a partir do evento ReservaCriada: cobra o pagamento e, conforme o resultado, confirma a reserva
 * (vende os lugares) ou a cancela devolvendo os lugares (compensação). A chamada ao gateway fica fora da transação
 * de banco de propósito: uma chamada de rede lenta não pode segurar locks.
 */
@Service
public class SagaDeCompra {

    public static final String CONSUMIDOR = "saga-compra";
    private static final Logger log = LoggerFactory.getLogger(SagaDeCompra.class);

    private final RepositorioReservas reservas;
    private final RepositorioLugares lugares;
    private final PortaPagamento pagamentos;
    private final Outbox outbox;
    private final TransactionTemplate transacao;
    private final Clock relogio;

    public SagaDeCompra(RepositorioReservas reservas, RepositorioLugares lugares, PortaPagamento pagamentos, Outbox outbox,
                        TransactionTemplate transacao, Clock relogio) {
        this.reservas = reservas;
        this.lugares = lugares;
        this.pagamentos = pagamentos;
        this.outbox = outbox;
        this.transacao = transacao;
        this.relogio = relogio;
    }

    /** @return o status final da reserva, ou null se a mensagem foi ignorada. */
    public Reserva.Status processar(UUID mensagemId, ReservaCriada evento) {
        if (outbox.jaProcessada(mensagemId, CONSUMIDOR)) {
            log.info("mensagem {} já processada pela saga; ignorando", mensagemId);
            return null;
        }
        var pendente = transacao.execute(st -> reservas.findById(evento.agregadoId()).filter(r -> r.getStatus() == Reserva.Status.PENDENTE).isPresent());
        if (!Boolean.TRUE.equals(pendente)) {
            log.info("reserva {} não está mais pendente; saga encerrada", evento.agregadoId());
            transacao.executeWithoutResult(st -> outbox.marcarProcessada(mensagemId, CONSUMIDOR));
            return null;
        }

        // passo 1: cobrar (fora da transação; indisponibilidade lança exceção e o consumidor tenta de novo)
        var resultado = pagamentos.cobrar(evento.agregadoId(), evento.valorTotal(), evento.tokenPagamento());

        // passo 2: aplicar o resultado e marcar a mensagem, atomicamente
        return transacao.execute(st -> aplicar(mensagemId, evento.agregadoId(), resultado));
    }

    private Reserva.Status aplicar(UUID mensagemId, UUID reservaId, ResultadoPagamento resultado) {
        var reserva = reservas.findById(reservaId).orElseThrow();
        if (reserva.getStatus() != Reserva.Status.PENDENTE) return reserva.getStatus();
        var agora = Instant.now(relogio);
        var lugaresDaReserva = lugares.bloquearPorIds(reserva.getLugares());
        if (resultado.aprovado()) {
            if (reserva.expirada(agora)) {
                // pagou tarde demais: compensa devolvendo os lugares; o estorno seria o próximo passo numa saga real
                reserva.cancelar("pagamento aprovado após o prazo; valor será estornado", lugaresDaReserva, agora);
                log.warn("reserva {} paga após expirar; cancelada com estorno", reservaId);
            } else {
                reserva.confirmar(lugaresDaReserva, agora);
                log.info("reserva {} confirmada, autorização {}", reservaId, resultado.autorizacao());
            }
        } else {
            reserva.cancelar("pagamento recusado: " + resultado.motivo(), lugaresDaReserva, agora);
            log.info("reserva {} cancelada: {}", reservaId, resultado.motivo());
        }
        outbox.registrar(reserva.drenarEventos());
        outbox.marcarProcessada(mensagemId, CONSUMIDOR);
        return reserva.getStatus();
    }
}
