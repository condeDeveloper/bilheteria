package br.com.conde.bilheteria.aplicacao.suporte;

import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioMensagensProcessadas;
import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioOutbox;
import br.com.conde.bilheteria.dominio.EventoDeDominio;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Grava eventos de domínio no outbox (dentro da transação de quem chama) e mantém o inbox dos consumidores.
 * Serialização via Jackson, com o tipo resolvido pelo nome simples do record, graças à interface selada.
 */
@Component
public class Outbox {

    /** Nome do tipo → classe do record, derivado das subclasses permitidas de EventoDeDominio. */
    public static final Map<String, Class<? extends EventoDeDominio>> TIPOS = Arrays.stream(EventoDeDominio.class.getPermittedSubclasses())
        .map(c -> c.asSubclass(EventoDeDominio.class))
        .collect(Collectors.toUnmodifiableMap(Class::getSimpleName, Function.identity()));

    private final RepositorioOutbox repositorio;
    private final RepositorioMensagensProcessadas processadas;
    private final ObjectMapper json;
    private final Clock relogio;

    public Outbox(RepositorioOutbox repositorio, RepositorioMensagensProcessadas processadas, ObjectMapper json, Clock relogio) {
        this.repositorio = repositorio;
        this.processadas = processadas;
        this.json = json;
        this.relogio = relogio;
    }

    public void registrar(Collection<? extends EventoDeDominio> eventos) {
        eventos.forEach(this::registrar);
    }

    public void registrar(EventoDeDominio evento) {
        try {
            repositorio.save(new MensagemOutbox(UUID.randomUUID(), evento.tipo(), evento.agregadoId(), json.writeValueAsString(evento), Instant.now(relogio)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("não foi possível serializar " + evento.tipo(), e);
        }
    }

    public EventoDeDominio desserializar(String tipo, String corpo) {
        var classe = TIPOS.get(tipo);
        if (classe == null) throw new IllegalArgumentException("tipo de evento desconhecido: " + tipo);
        try {
            return json.readValue(corpo, classe);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("corpo inválido para " + tipo, e);
        }
    }

    public boolean jaProcessada(UUID mensagemId, String consumidor) {
        return processadas.existsById(new MensagemProcessada.Chave(mensagemId, consumidor));
    }

    public void marcarProcessada(UUID mensagemId, String consumidor) {
        processadas.save(new MensagemProcessada(mensagemId, consumidor, Instant.now(relogio)));
    }
}
