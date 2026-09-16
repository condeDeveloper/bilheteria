package br.com.conde.bilheteria.aplicacao;

import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioEventos;
import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioLugares;
import br.com.conde.bilheteria.dominio.Evento;
import br.com.conde.bilheteria.dominio.Excecoes.RecursoNaoEncontradoException;
import br.com.conde.bilheteria.dominio.Lugar;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ServicoEventos {

    public record NovoSetor(String nome, BigDecimal preco, int fileiras, int lugaresPorFileira) {
    }

    public record NovoEvento(String nome, String local, Instant dataHora, List<NovoSetor> setores) {
    }

    public record DisponibilidadeSetor(UUID setorId, String nome, BigDecimal preco, int capacidade, int livres, List<String> lugaresLivres) {
    }

    public record Disponibilidade(UUID eventoId, String nome, Evento.Status status, int capacidade, int livres, List<DisponibilidadeSetor> setores) {
    }

    private final RepositorioEventos eventos;
    private final RepositorioLugares lugares;
    private final Clock relogio;

    public ServicoEventos(RepositorioEventos eventos, RepositorioLugares lugares, Clock relogio) {
        this.eventos = eventos;
        this.lugares = lugares;
        this.relogio = relogio;
    }

    @Transactional
    public Evento criar(NovoEvento comando) {
        var evento = Evento.criar(comando.nome(), comando.local(), comando.dataHora(), Instant.now(relogio));
        var todosOsLugares = new ArrayList<Lugar>();
        for (var s : comando.setores() == null ? List.<NovoSetor>of() : comando.setores()) {
            var setor = evento.adicionarSetor(s.nome(), s.preco(), s.fileiras(), s.lugaresPorFileira());
            todosOsLugares.addAll(setor.gerarLugares(s.fileiras(), s.lugaresPorFileira()));
        }
        eventos.save(evento);
        lugares.saveAll(todosOsLugares);
        return evento;
    }

    @Transactional
    @CacheEvict(cacheNames = "disponibilidade", key = "#eventoId")
    public Evento abrirVendas(UUID eventoId) {
        var evento = obter(eventoId);
        evento.abrirVendas();
        return evento;
    }

    @Transactional(readOnly = true)
    public Evento obter(UUID eventoId) {
        return eventos.findById(eventoId).orElseThrow(() -> new RecursoNaoEncontradoException("evento", eventoId));
    }

    /** Mapa de lugares livres por setor. Consulta cara e muito lida: fica em cache por alguns segundos. */
    @Transactional(readOnly = true)
    @Cacheable(cacheNames = "disponibilidade", key = "#eventoId")
    public Disponibilidade disponibilidade(UUID eventoId) {
        var evento = obter(eventoId);
        var todos = lugares.findByEventoIdOrderByCodigo(eventoId);
        var setores = evento.getSetores().stream().map(setor -> {
            var doSetor = todos.stream().filter(l -> l.getSetor().getId().equals(setor.getId())).toList();
            var livres = doSetor.stream().filter(Lugar::livre).map(Lugar::getCodigo).toList();
            return new DisponibilidadeSetor(setor.getId(), setor.getNome(), setor.getPreco(), setor.getCapacidade(), livres.size(), livres);
        }).toList();
        var capacidade = setores.stream().mapToInt(DisponibilidadeSetor::capacidade).sum();
        var livres = setores.stream().mapToInt(DisponibilidadeSetor::livres).sum();
        return new Disponibilidade(eventoId, evento.getNome(), evento.getStatus(), capacidade, livres, setores);
    }
}
