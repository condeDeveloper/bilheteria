package br.com.conde.bilheteria.infra.mensageria;

import br.com.conde.bilheteria.aplicacao.SagaDeCompra;
import br.com.conde.bilheteria.aplicacao.ServicoIngressos;
import br.com.conde.bilheteria.aplicacao.portas.Portas.PagamentoIndisponivelException;
import br.com.conde.bilheteria.aplicacao.suporte.Outbox;
import br.com.conde.bilheteria.dominio.EventoDeDominio;
import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaConfirmada;
import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaCriada;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Consumidores do tópico de reservas. Cada um tem seu grupo (recebe todas as mensagens) e trata só os tipos que lhe
 * interessam. Falhas transitórias vão para tópicos de retry com backoff exponencial e, no fim, para o DLT.
 */
@Component
public class Consumidores {

    private static final Logger log = LoggerFactory.getLogger(Consumidores.class);

    private final SagaDeCompra saga;
    private final ServicoIngressos ingressos;
    private final Outbox outbox;
    private final ObjectMapper json;

    public Consumidores(SagaDeCompra saga, ServicoIngressos ingressos, Outbox outbox, ObjectMapper json) {
        this.saga = saga;
        this.ingressos = ingressos;
        this.outbox = outbox;
        this.json = json;
    }

    @RetryableTopic(attempts = "4", backoff = @Backoff(delay = 500, multiplier = 2.0), autoCreateTopics = "true",
        dltStrategy = DltStrategy.FAIL_ON_ERROR, retryTopicSuffix = ".retry", dltTopicSuffix = ".dlt",
        include = {PagamentoIndisponivelException.class, TransientDataAccessException.class, ObjectOptimisticLockingFailureException.class})
    @KafkaListener(topics = Topicos.RESERVAS, groupId = SagaDeCompra.CONSUMIDOR)
    public void saga(String mensagem) {
        var envelope = ler(mensagem);
        if (outbox.desserializar(envelope.tipo(), envelope.dados()) instanceof ReservaCriada criada) {
            saga.processar(envelope.id(), criada);
        }
    }

    @RetryableTopic(attempts = "4", backoff = @Backoff(delay = 500, multiplier = 2.0), autoCreateTopics = "true",
        dltStrategy = DltStrategy.FAIL_ON_ERROR, retryTopicSuffix = ".retry", dltTopicSuffix = ".dlt",
        include = {TransientDataAccessException.class, ObjectOptimisticLockingFailureException.class})
    @KafkaListener(topics = Topicos.RESERVAS, groupId = ServicoIngressos.CONSUMIDOR)
    public void emissao(String mensagem) {
        var envelope = ler(mensagem);
        if (outbox.desserializar(envelope.tipo(), envelope.dados()) instanceof ReservaConfirmada confirmada) {
            ingressos.emitir(envelope.id(), confirmada);
        }
    }

    @DltHandler
    public void mortas(String mensagem, @Header(KafkaHeaders.RECEIVED_TOPIC) String topico, @Header(name = KafkaHeaders.EXCEPTION_MESSAGE, required = false) String erro) {
        log.error("mensagem no DLT {}: {} | {}", topico, erro, mensagem);
    }

    private Envelope ler(String mensagem) {
        try {
            return json.readValue(mensagem, Envelope.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("envelope inválido", e);
        }
    }

    /** Exposto para testes: tipos conhecidos pelo barramento. */
    static boolean conhecido(String tipo) {
        return Outbox.TIPOS.containsKey(tipo) && EventoDeDominio.class.isAssignableFrom(Outbox.TIPOS.get(tipo));
    }
}
