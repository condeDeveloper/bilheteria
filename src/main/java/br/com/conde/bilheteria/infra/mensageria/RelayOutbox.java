package br.com.conde.bilheteria.infra.mensageria;

import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioOutbox;
import br.com.conde.bilheteria.aplicacao.suporte.MensagemOutbox;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Publica as mensagens pendentes do outbox no Kafka, em ordem, com a chave igual ao id do agregado para que todos
 * os eventos de uma reserva caiam na mesma partição e cheguem em ordem. Entrega pelo menos uma vez.
 */
@Component
public class RelayOutbox {

    public static final int MAXIMO_DE_TENTATIVAS = 10;
    private static final Logger log = LoggerFactory.getLogger(RelayOutbox.class);

    private final RepositorioOutbox repositorio;
    private final KafkaTemplate<String, String> kafka;
    private final ObjectMapper json;
    private final Clock relogio;
    private final TransactionTemplate transacao;
    private final Counter publicadas;
    private final Counter falhas;

    public RelayOutbox(RepositorioOutbox repositorio, KafkaTemplate<String, String> kafka, ObjectMapper json, Clock relogio,
                       TransactionTemplate transacao, MeterRegistry metricas) {
        this.repositorio = repositorio;
        this.kafka = kafka;
        this.json = json;
        this.relogio = relogio;
        this.transacao = transacao;
        this.publicadas = Counter.builder("bilheteria.outbox.publicadas").description("Mensagens do outbox publicadas no Kafka").register(metricas);
        this.falhas = Counter.builder("bilheteria.outbox.falhas").description("Falhas ao publicar mensagens do outbox").register(metricas);
        metricas.gauge("bilheteria.outbox.pendentes", repositorio, RepositorioOutbox::countByPublicadoEmIsNull);
    }

    @Scheduled(fixedDelayString = "${bilheteria.outbox.intervalo:1000}")
    public void ciclo() {
        try {
            var n = publicarPendentes(100);
            if (n > 0) log.debug("outbox: {} mensagem(ns) publicada(s)", n);
        } catch (Exception e) {
            log.error("erro no ciclo do relay do outbox", e);
        }
    }

    /**
     * Lê, publica e marca dentro de uma transação. Usa TransactionTemplate em vez de @Transactional porque o método é
     * chamado pelo próprio bean (ciclo): auto-invocação não passa pelo proxy e a anotação seria ignorada.
     */
    public int publicarPendentes(int lote) {
        var total = transacao.execute(st -> publicar(lote));
        return total == null ? 0 : total;
    }

    private int publicar(int lote) {
        var pendentes = repositorio.findByPublicadoEmIsNullAndTentativasLessThanOrderByCriadoEmAsc(MAXIMO_DE_TENTATIVAS, PageRequest.of(0, lote));
        var total = 0;
        for (var m : pendentes) {
            try {
                kafka.send(Topicos.RESERVAS, m.getAgregadoId().toString(), json.writeValueAsString(envelope(m))).get(10, TimeUnit.SECONDS);
                m.marcarPublicada(Instant.now(relogio));
                publicadas.increment();
                total++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ExecutionException | TimeoutException | JsonProcessingException e) {
                m.registrarFalha(e.getMessage());
                falhas.increment();
                log.warn("falha ao publicar {} ({}), tentativa {}", m.getId(), m.getTipo(), m.getTentativas());
            }
        }
        return total;
    }

    private Envelope envelope(MensagemOutbox m) {
        return new Envelope(m.getId(), m.getTipo(), m.getAgregadoId(), Instant.now(relogio), m.getCorpo());
    }
}
