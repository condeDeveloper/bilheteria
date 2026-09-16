package br.com.conde.bilheteria.dominio;

import br.com.conde.bilheteria.aplicacao.suporte.AssinadorDeIngressos;
import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaCancelada;
import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaConfirmada;
import br.com.conde.bilheteria.dominio.EventoDeDominio.ReservaCriada;
import br.com.conde.bilheteria.dominio.Excecoes.ConflitoException;
import br.com.conde.bilheteria.dominio.Excecoes.RegraDeNegocioException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DominioTest {

    private static final Instant AGORA = Instant.parse("2026-09-16T12:00:00Z");

    private static Evento eventoComSetores() {
        var evento = Evento.criar("Show", "Arena", AGORA.plus(Duration.ofDays(30)), AGORA);
        evento.adicionarSetor("Pista", new BigDecimal("100"), 2, 5);
        evento.adicionarSetor("Cadeira Superior", new BigDecimal("250.5"), 1, 4);
        return evento;
    }

    @Test
    void geraLugaresPorSetorComCodigosLegiveis() {
        var evento = eventoComSetores();
        var pista = evento.getSetores().get(0);
        var lugares = pista.gerarLugares(2, 5);
        assertThat(lugares).hasSize(10);
        assertThat(lugares.get(0).getCodigo()).isEqualTo("PISTA-A-1");
        assertThat(lugares.get(9).getCodigo()).isEqualTo("PISTA-B-5");
        assertThat(lugares).allMatch(Lugar::livre);
        assertThat(evento.getSetores().get(1).getCapacidade()).isEqualTo(4);
        assertThat(evento.getSetores().get(1).getPreco()).isEqualByComparingTo("250.50");
    }

    @Test
    void validaEventoESetores() {
        assertThatThrownBy(() -> Evento.criar("x", "y", AGORA.minusSeconds(1), AGORA)).isInstanceOf(RegraDeNegocioException.class).hasMessageContaining("futuro");
        var evento = eventoComSetores();
        assertThatThrownBy(() -> evento.adicionarSetor("pista", BigDecimal.TEN, 1, 1)).hasMessageContaining("já existe");
        assertThatThrownBy(() -> evento.adicionarSetor("VIP", new BigDecimal("-1"), 1, 1)).hasMessageContaining("preço");
        assertThatThrownBy(() -> evento.adicionarSetor("VIP", BigDecimal.TEN, 27, 1)).hasMessageContaining("fileiras");
        assertThatThrownBy(() -> Evento.criar("Vazio", "", AGORA.plusSeconds(10), AGORA).abrirVendas()).hasMessageContaining("sem setores");
        evento.abrirVendas();
        assertThat(evento.vendasAbertas()).isTrue();
        assertThatThrownBy(evento::abrirVendas).hasMessageContaining("não pode abrir");
        assertThatThrownBy(() -> evento.adicionarSetor("Tarde", BigDecimal.ONE, 1, 1)).hasMessageContaining("rascunho");
    }

    @Test
    void reservaTravaLugaresCalculaTotalEEmiteEvento() {
        var evento = eventoComSetores();
        var pista = evento.getSetores().get(0).gerarLugares(2, 5);
        var cadeira = evento.getSetores().get(1).gerarLugares(1, 4);
        var escolhidos = List.of(pista.get(0), pista.get(1), cadeira.get(0));

        var reserva = Reserva.criar(evento.getId(), "ana", escolhidos, "tok-1234", AGORA);
        assertThat(reserva.getStatus()).isEqualTo(Reserva.Status.PENDENTE);
        assertThat(reserva.getValorTotal()).isEqualByComparingTo("450.50");
        assertThat(reserva.getExpiraEm()).isEqualTo(AGORA.plus(Reserva.PRAZO_PADRAO));
        assertThat(escolhidos).allMatch(l -> l.getStatus() == Lugar.Status.RESERVADO && reserva.getId().equals(l.getReservaId()));
        var eventos = reserva.drenarEventos();
        assertThat(eventos).singleElement().isInstanceOf(ReservaCriada.class);
        assertThat(((ReservaCriada) eventos.get(0)).lugares()).hasSize(3);
        assertThat(reserva.drenarEventos()).isEmpty();

        assertThatThrownBy(() -> Reserva.criar(evento.getId(), "bruno", List.of(pista.get(0)), "tok", AGORA)).isInstanceOf(ConflitoException.class);
        assertThatThrownBy(() -> Reserva.criar(evento.getId(), "bruno", List.of(), "tok", AGORA)).hasMessageContaining("ao menos um");
        var novePedidos = java.util.stream.Stream.concat(pista.subList(2, 10).stream(), cadeira.subList(1, 2).stream()).toList();
        assertThatThrownBy(() -> Reserva.criar(evento.getId(), "bruno", novePedidos, "tok", AGORA)).hasMessageContaining("máximo");
    }

    @Test
    void confirmarVendeECancelarDevolveLugares() {
        var evento = eventoComSetores();
        var lugares = evento.getSetores().get(0).gerarLugares(1, 3);
        var reserva = Reserva.criar(evento.getId(), "ana", lugares, "tok", AGORA);
        reserva.drenarEventos();

        reserva.confirmar(lugares, AGORA.plusSeconds(60));
        assertThat(reserva.getStatus()).isEqualTo(Reserva.Status.CONFIRMADA);
        assertThat(lugares).allMatch(l -> l.getStatus() == Lugar.Status.VENDIDO);
        assertThat(reserva.drenarEventos()).singleElement().isInstanceOf(ReservaConfirmada.class);
        assertThatThrownBy(() -> reserva.cancelar("x", lugares, AGORA)).hasMessageContaining("CONFIRMADA");

        var outra = Reserva.criar(evento.getId(), "bruno", evento.getSetores().get(1).gerarLugares(1, 2), "tok", AGORA);
        var seusLugares = outra.getLugares();
        var lugaresDaOutra = evento.getSetores().get(1).gerarLugares(1, 2); // objetos distintos com outra reserva: não podem ser liberados por ela
        outra.drenarEventos();
        outra.cancelar("desisti", lugaresDaOutra, AGORA);
        assertThat(outra.getStatus()).isEqualTo(Reserva.Status.CANCELADA);
        assertThat(lugaresDaOutra).allMatch(Lugar::livre);
        assertThat(outra.drenarEventos()).singleElement().isInstanceOf(ReservaCancelada.class);
        assertThat(seusLugares).hasSize(2);
    }

    @Test
    void expiracaoEConfirmacaoTardia() {
        var evento = eventoComSetores();
        var lugares = evento.getSetores().get(0).gerarLugares(1, 1);
        var reserva = Reserva.criar(evento.getId(), "ana", lugares, "tok", AGORA);
        var depois = AGORA.plus(Reserva.PRAZO_PADRAO).plusSeconds(1);
        assertThat(reserva.expirada(depois)).isTrue();
        assertThat(reserva.expirada(AGORA.plusSeconds(1))).isFalse();
        assertThatThrownBy(() -> reserva.confirmar(lugares, depois)).hasMessageContaining("expirada");
        reserva.expirar(lugares, depois);
        assertThat(reserva.getStatus()).isEqualTo(Reserva.Status.EXPIRADA);
        assertThat(lugares.get(0).livre()).isTrue();
        assertThat(reserva.expirada(depois)).isFalse();
    }

    @Test
    void lugarLiberaSoParaAReservaDona() {
        var evento = eventoComSetores();
        var lugar = evento.getSetores().get(0).gerarLugares(1, 1).get(0);
        var dona = UUID.randomUUID();
        lugar.reservar(dona);
        lugar.liberar(UUID.randomUUID());
        assertThat(lugar.getStatus()).isEqualTo(Lugar.Status.RESERVADO);
        assertThatThrownBy(() -> lugar.vender(UUID.randomUUID())).isInstanceOf(ConflitoException.class);
        lugar.vender(dona);
        lugar.liberar(dona);
        assertThat(lugar.getStatus()).as("vendido não volta ao estoque por liberação").isEqualTo(Lugar.Status.VENDIDO);
    }

    @Test
    void ingressoAssinadoEUsoUnico() {
        var assinador = new AssinadorDeIngressos("uma-chave-de-teste-bem-comprida");
        var reserva = UUID.randomUUID();
        var lugar = UUID.randomUUID();
        var codigo = assinador.assinar(reserva, lugar);
        assertThat(codigo).doesNotContain("=", "+", "/");
        assertThat(assinador.valido(codigo)).isTrue();
        assertThat(assinador.valido(codigo.substring(0, codigo.length() - 2) + "AA")).isFalse();
        assertThat(assinador.valido("nao-e-base64!!")).isFalse();
        assertThat(new AssinadorDeIngressos("outra-chave-de-teste-comprida").valido(codigo)).as("outra chave não valida").isFalse();

        var ingresso = Ingresso.emitir(reserva, lugar, codigo, AGORA);
        ingresso.utilizar(AGORA.plusSeconds(5));
        assertThat(ingresso.getStatus()).isEqualTo(Ingresso.Status.UTILIZADO);
        assertThatThrownBy(() -> ingresso.utilizar(AGORA.plusSeconds(6))).hasMessageContaining("já utilizado");
    }
}
