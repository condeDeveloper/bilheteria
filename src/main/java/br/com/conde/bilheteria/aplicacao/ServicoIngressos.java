package br.com.conde.bilheteria.aplicacao;

import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioIngressos;
import br.com.conde.bilheteria.aplicacao.suporte.AssinadorDeIngressos;
import br.com.conde.bilheteria.aplicacao.suporte.Outbox;
import br.com.conde.bilheteria.dominio.EventoDeDominio.IngressoUtilizado;
import br.com.conde.bilheteria.dominio.EventoDeDominio.IngressosEmitidos;
import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaConfirmada;
import br.com.conde.bilheteria.dominio.Excecoes.RecursoNaoEncontradoException;
import br.com.conde.bilheteria.dominio.Excecoes.RegraDeNegocioException;
import br.com.conde.bilheteria.dominio.Ingresso;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Emissão de ingressos a partir de ReservaConfirmada e check-in na portaria. */
@Service
public class ServicoIngressos {

    public static final String CONSUMIDOR = "emissao-ingressos";
    private static final Logger log = LoggerFactory.getLogger(ServicoIngressos.class);

    private final RepositorioIngressos ingressos;
    private final AssinadorDeIngressos assinador;
    private final Outbox outbox;
    private final Clock relogio;

    public ServicoIngressos(RepositorioIngressos ingressos, AssinadorDeIngressos assinador, Outbox outbox, Clock relogio) {
        this.ingressos = ingressos;
        this.assinador = assinador;
        this.outbox = outbox;
        this.relogio = relogio;
    }

    /** Idempotente em dois níveis: pelo inbox da mensagem e pela existência de ingressos da reserva. */
    @Transactional
    public List<Ingresso> emitir(UUID mensagemId, ReservaConfirmada evento) {
        if (outbox.jaProcessada(mensagemId, CONSUMIDOR) || ingressos.existsByReservaId(evento.agregadoId())) {
            log.info("ingressos da reserva {} já emitidos; ignorando", evento.agregadoId());
            return ingressos.findByReservaIdOrderByEmitidoEm(evento.agregadoId());
        }
        var agora = Instant.now(relogio);
        var emitidos = evento.lugares().stream()
            .map(lugarId -> Ingresso.emitir(evento.agregadoId(), lugarId, assinador.assinar(evento.agregadoId(), lugarId), agora))
            .toList();
        ingressos.saveAll(emitidos);
        outbox.registrar(new IngressosEmitidos(evento.agregadoId(), emitidos.size(), agora));
        outbox.marcarProcessada(mensagemId, CONSUMIDOR);
        log.info("{} ingresso(s) emitido(s) para a reserva {}", emitidos.size(), evento.agregadoId());
        return emitidos;
    }

    @Transactional(readOnly = true)
    public List<Ingresso> daReserva(UUID reservaId) {
        return ingressos.findByReservaIdOrderByEmitidoEm(reservaId);
    }

    /** Portaria: valida a assinatura antes de ir ao banco, e um ingresso só entra uma vez. */
    @Transactional
    public Ingresso checkIn(String codigo) {
        if (!assinador.valido(codigo)) throw new RegraDeNegocioException("código de ingresso inválido ou adulterado");
        var ingresso = ingressos.findByCodigo(codigo).orElseThrow(() -> new RecursoNaoEncontradoException("ingresso", codigo));
        var agora = Instant.now(relogio);
        ingresso.utilizar(agora);
        outbox.registrar(new IngressoUtilizado(ingresso.getReservaId(), ingresso.getId(), agora));
        return ingresso;
    }
}
