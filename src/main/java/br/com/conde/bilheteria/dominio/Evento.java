package br.com.conde.bilheteria.dominio;

import br.com.conde.bilheteria.dominio.Excecoes.RegraDeNegocioException;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Um show, jogo ou peça: tem setores com preço e lugares numerados. */
@Entity
@Table(name = "eventos")
public class Evento {

    public enum Status { RASCUNHO, VENDAS_ABERTAS, ENCERRADO }

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String nome;

    @Column(nullable = false, length = 120)
    private String local;

    @Column(name = "data_hora", nullable = false)
    private Instant dataHora;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Status status;

    @Column(name = "criado_em", nullable = false)
    private Instant criadoEm;

    @Version
    private long versao;

    @OneToMany(mappedBy = "evento", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<Setor> setores = new ArrayList<>();

    protected Evento() {
    }

    public static Evento criar(String nome, String local, Instant dataHora, Instant agora) {
        if (nome == null || nome.isBlank()) throw new RegraDeNegocioException("nome do evento obrigatório");
        if (dataHora == null || !dataHora.isAfter(agora)) throw new RegraDeNegocioException("evento precisa ser no futuro");
        var e = new Evento();
        e.id = UUID.randomUUID();
        e.nome = nome.trim();
        e.local = local == null ? "" : local.trim();
        e.dataHora = dataHora;
        e.status = Status.RASCUNHO;
        e.criadoEm = agora;
        return e;
    }

    public Setor adicionarSetor(String nome, java.math.BigDecimal preco, int fileiras, int lugaresPorFileira) {
        if (status != Status.RASCUNHO) throw new RegraDeNegocioException("setores só podem ser adicionados em rascunho");
        if (setores.stream().anyMatch(s -> s.getNome().equalsIgnoreCase(nome))) throw new RegraDeNegocioException("setor '" + nome + "' já existe");
        var setor = Setor.criar(this, nome, preco, fileiras, lugaresPorFileira);
        setores.add(setor);
        return setor;
    }

    public void abrirVendas() {
        if (status != Status.RASCUNHO) throw new RegraDeNegocioException("evento " + status + " não pode abrir vendas");
        if (setores.isEmpty()) throw new RegraDeNegocioException("evento sem setores");
        status = Status.VENDAS_ABERTAS;
    }

    public void encerrar() {
        status = Status.ENCERRADO;
    }

    public boolean vendasAbertas() {
        return status == Status.VENDAS_ABERTAS;
    }

    public UUID getId() { return id; }
    public String getNome() { return nome; }
    public String getLocal() { return local; }
    public Instant getDataHora() { return dataHora; }
    public Status getStatus() { return status; }
    public Instant getCriadoEm() { return criadoEm; }
    public long getVersao() { return versao; }
    public List<Setor> getSetores() { return List.copyOf(setores); }
}
