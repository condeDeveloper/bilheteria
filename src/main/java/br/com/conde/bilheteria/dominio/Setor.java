package br.com.conde.bilheteria.dominio;

import br.com.conde.bilheteria.dominio.Excecoes.RegraDeNegocioException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Área do evento com um preço: Pista, Cadeira Superior... Os lugares são gerados em fileiras A, B, C. */
@Entity
@Table(name = "setores")
public class Setor {

    public static final int MAXIMO_DE_LUGARES = 5_000;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evento_id")
    private Evento evento;

    @Column(nullable = false, length = 60)
    private String nome;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal preco;

    @Column(nullable = false)
    private int capacidade;

    protected Setor() {
    }

    static Setor criar(Evento evento, String nome, BigDecimal preco, int fileiras, int lugaresPorFileira) {
        if (nome == null || nome.isBlank()) throw new RegraDeNegocioException("nome do setor obrigatório");
        if (preco == null || preco.signum() < 0) throw new RegraDeNegocioException("preço inválido no setor " + nome);
        if (fileiras < 1 || fileiras > 26) throw new RegraDeNegocioException("fileiras entre 1 e 26 no setor " + nome);
        if (lugaresPorFileira < 1) throw new RegraDeNegocioException("lugares por fileira deve ser positivo no setor " + nome);
        if (fileiras * lugaresPorFileira > MAXIMO_DE_LUGARES) throw new RegraDeNegocioException("setor com mais de " + MAXIMO_DE_LUGARES + " lugares");
        var s = new Setor();
        s.id = UUID.randomUUID();
        s.evento = evento;
        s.nome = nome.trim();
        s.preco = preco.setScale(2, java.math.RoundingMode.HALF_EVEN);
        s.capacidade = fileiras * lugaresPorFileira;
        return s;
    }

    /** Gera os lugares do setor: prefixo do setor + fileira + número, por exemplo "PISTA-A-12". */
    public List<Lugar> gerarLugares(int fileiras, int lugaresPorFileira) {
        var prefixo = nome.toUpperCase().replaceAll("[^A-Z0-9]", "").substring(0, Math.min(6, nome.replaceAll("[^A-Za-z0-9]", "").length()));
        var lugares = new ArrayList<Lugar>(fileiras * lugaresPorFileira);
        for (var f = 0; f < fileiras; f++) {
            var letra = (char) ('A' + f);
            for (var n = 1; n <= lugaresPorFileira; n++) {
                lugares.add(Lugar.criar(evento, this, prefixo + "-" + letra + "-" + n));
            }
        }
        return lugares;
    }

    public UUID getId() { return id; }
    public String getNome() { return nome; }
    public BigDecimal getPreco() { return preco; }
    public int getCapacidade() { return capacidade; }
    public Evento getEvento() { return evento; }
}
