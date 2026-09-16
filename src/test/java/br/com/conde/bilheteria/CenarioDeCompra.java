package br.com.conde.bilheteria;

import br.com.conde.bilheteria.aplicacao.portas.Portas.PagamentoIndisponivelException;
import br.com.conde.bilheteria.aplicacao.portas.Portas.PortaPagamento;
import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioLugares;
import br.com.conde.bilheteria.aplicacao.portas.Repositorios.RepositorioOutbox;
import br.com.conde.bilheteria.dominio.Lugar;
import br.com.conde.bilheteria.infra.agendamento.AgendadorDeExpiracao;
import br.com.conde.bilheteria.infra.pagamento.GatewayDePagamentoHttp;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Cenário completo pela API HTTP. As subclasses só escolhem a infraestrutura: H2 com Kafka embutido (rápido) ou
 * PostgreSQL, Kafka e Redis reais com Testcontainers. O relógio é ajustável para testar expiração sem esperar.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(CenarioDeCompra.RelogioDeTeste.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public abstract class CenarioDeCompra {

    /** Relógio do sistema com deslocamento ajustável. */
    public static class RelogioAjustavel extends Clock {
        private volatile Duration deslocamento = Duration.ZERO;

        public void avancar(Duration d) { deslocamento = deslocamento.plus(d); }
        public void zerar() { deslocamento = Duration.ZERO; }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.now().plus(deslocamento); }
    }

    @TestConfiguration
    public static class RelogioDeTeste {
        @Bean @Primary
        RelogioAjustavel relogioAjustavel() { return new RelogioAjustavel(); }
    }

    @Autowired protected TestRestTemplate http;
    @Autowired protected ObjectMapper json;
    @Autowired protected RelogioAjustavel relogio;
    @Autowired protected AgendadorDeExpiracao expirador;
    @Autowired protected RepositorioLugares lugares;
    @Autowired protected RepositorioOutbox outbox;
    @Autowired protected PortaPagamento pagamentos;
    @Autowired protected CircuitBreakerRegistry breakers;

    @AfterEach
    void limpar() {
        relogio.zerar();
        breakers.circuitBreaker(GatewayDePagamentoHttp.NOME).reset();
    }

    // ---------- cenários ----------

    @Test
    @Order(1)
    void autenticacaoEAutorizacao() {
        assertThat(http.getForEntity("/api/reservas", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(post("/api/auth/token", null, Map.of("usuario", "ana", "senha", "errada")).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var cliente = token("ana");
        var negado = post("/api/eventos", cliente, corpoEvento());
        assertThat(negado.getStatusCode()).as("cliente não cria evento").isEqualTo(HttpStatus.FORBIDDEN);
        var invalido = post("/api/eventos", token("organizador"), Map.of("nome", "", "setores", List.of()));
        assertThat(invalido.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalido.getBody()).contains("\"erros\"");
    }

    @Test
    @Order(2)
    void fluxoFelizAteOCheckIn() throws Exception {
        var organizador = token("organizador");
        var ana = token("ana");
        var eventoId = criarEventoAberto(organizador);

        var antes = json.readTree(http.getForObject("/api/eventos/" + eventoId + "/disponibilidade", String.class));
        assertThat(antes.get("livres").asInt()).isEqualTo(14);

        var resposta = reservar(ana, eventoId, List.of("PISTA-A-1", "PISTA-A-2"), "tok-1234", null);
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var reserva = json.readTree(resposta.getBody());
        assertThat(reserva.get("status").asText()).isEqualTo("PENDENTE");
        assertThat(reserva.get("valorTotal").decimalValue()).isEqualByComparingTo("200.00");
        var reservaId = reserva.get("id").asText();

        var depois = json.readTree(http.getForObject("/api/eventos/" + eventoId + "/disponibilidade", String.class));
        assertThat(depois.get("livres").asInt()).as("cache invalidado ao reservar").isEqualTo(12);

        esperarStatus(ana, reservaId, "CONFIRMADA");
        assertThat(lugares.countByEventoIdAndStatus(eventoId, Lugar.Status.VENDIDO)).isEqualTo(2);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            var ingressos = json.readTree(get("/api/reservas/" + reservaId + "/ingressos", ana).getBody());
            assertThat(ingressos).hasSize(2);
        });
        var ingressos = json.readTree(get("/api/reservas/" + reservaId + "/ingressos", ana).getBody());
        var codigo = ingressos.get(0).get("codigo").asText();

        var checkIn = post("/api/ingressos/" + codigo + "/check-in", organizador, null);
        assertThat(checkIn.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(checkIn.getBody()).get("status").asText()).isEqualTo("UTILIZADO");
        assertThat(post("/api/ingressos/" + codigo + "/check-in", organizador, null).getStatusCode()).as("segunda entrada").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post("/api/ingressos/" + codigo.substring(0, codigo.length() - 3) + "xyz/check-in", organizador, null).getStatusCode()).as("código adulterado").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(post("/api/ingressos/" + codigo + "/check-in", ana, null).getStatusCode()).as("cliente não faz check-in").isEqualTo(HttpStatus.FORBIDDEN);

        await().atMost(Duration.ofSeconds(15)).until(() -> outbox.countByPublicadoEmIsNull() == 0);
    }

    @Test
    @Order(3)
    void idempotenciaDaCriacaoDeReserva() throws Exception {
        var ana = token("ana");
        var eventoId = criarEventoAberto(token("organizador"));
        var chave = "chave-" + UUID.randomUUID();

        var primeira = reservar(ana, eventoId, List.of("CADEIR-A-1"), "tok-1234", chave);
        var segunda = reservar(ana, eventoId, List.of("CADEIR-A-1"), "tok-1234", chave);
        assertThat(primeira.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(segunda.getStatusCode()).as("repetição devolve a mesma reserva, sem erro de lugar ocupado").isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(segunda.getBody()).get("id")).isEqualTo(json.readTree(primeira.getBody()).get("id"));

        var outro = reservar(token("bruno"), eventoId, List.of("CADEIR-A-2"), "tok-1234", chave);
        assertThat(outro.getStatusCode()).as("chave de outro cliente").isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @Order(4)
    void concorrenciaNaoVendeOMesmoLugarDuasVezes() throws Exception {
        var eventoId = criarEventoAberto(token("organizador"));
        var tokens = List.of(token("ana"), token("bruno"));
        var threads = 24;
        var largada = new CountDownLatch(1);
        var criadas = new AtomicInteger();
        var conflitos = new AtomicInteger();
        var outros = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(threads)) {
            for (var k = 0; k < threads; k++) {
                var token = tokens.get(k % 2);
                executor.submit(() -> {
                    largada.await();
                    var r = reservar(token, eventoId, List.of("PISTA-B-3"), "tok-1234", null);
                    if (r.getStatusCode() == HttpStatus.CREATED) criadas.incrementAndGet();
                    else if (r.getStatusCode() == HttpStatus.CONFLICT) conflitos.incrementAndGet();
                    else outros.incrementAndGet();
                    return null;
                });
            }
            largada.countDown();
            executor.shutdown();
            assertThat(executor.awaitTermination(60, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(criadas.get()).as("exatamente uma reserva vence a disputa").isEqualTo(1);
        assertThat(conflitos.get()).isEqualTo(threads - 1);
        assertThat(outros.get()).isZero();
        assertThat(lugares.findByEventoIdOrderByCodigo(eventoId).stream().filter(l -> l.getCodigo().equals("PISTA-B-3")).findFirst().orElseThrow().getStatus())
            .isIn(Lugar.Status.RESERVADO, Lugar.Status.VENDIDO);
    }

    @Test
    @Order(5)
    void pagamentoRecusadoCancelaEDevolveOsLugares() throws Exception {
        var ana = token("ana");
        var eventoId = criarEventoAberto(token("organizador"));
        var resposta = reservar(ana, eventoId, List.of("PISTA-A-4", "PISTA-A-5"), "tok-0000", null);
        var reservaId = json.readTree(resposta.getBody()).get("id").asText();
        assertThat(lugares.countByEventoIdAndStatus(eventoId, Lugar.Status.RESERVADO)).isEqualTo(2);

        var cancelada = esperarStatus(ana, reservaId, "CANCELADA");
        assertThat(cancelada.get("motivo").asText()).contains("recusado");
        assertThat(lugares.countByEventoIdAndStatus(eventoId, Lugar.Status.LIVRE)).as("compensação devolveu os lugares").isEqualTo(14);
        var disponibilidade = json.readTree(http.getForObject("/api/eventos/" + eventoId + "/disponibilidade", String.class));
        assertThat(disponibilidade.get("livres").asInt()).isEqualTo(14);
        assertThat(get("/api/reservas/" + reservaId + "/ingressos", ana).getBody()).isEqualTo("[]");
    }

    @Test
    @Order(6)
    void cancelamentoPeloClienteEExpiracaoPeloRelogio() throws Exception {
        var ana = token("ana");
        var bruno = token("bruno");
        var eventoId = criarEventoAberto(token("organizador"));

        // gateway fora (500): a saga não conclui, a reserva fica pendente e o cliente cancela
        var r1 = json.readTree(reservar(ana, eventoId, List.of("PISTA-A-1"), "tok-5000", null).getBody()).get("id").asText();
        var cancelamento = post("/api/reservas/" + r1 + "/cancelamento", ana, null);
        assertThat(cancelamento.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(json.readTree(cancelamento.getBody()).get("status").asText()).isEqualTo("CANCELADA");
        assertThat(post("/api/reservas/" + r1 + "/cancelamento", bruno, null).getStatusCode()).as("reserva de outro cliente").isEqualTo(HttpStatus.NOT_FOUND);

        // outra reserva pendente com o gateway fora: o relógio avança além do prazo e o agendador a expira
        var r2 = json.readTree(reservar(bruno, eventoId, List.of("PISTA-A-2"), "tok-5000", null).getBody()).get("id").asText();
        assertThat(expirador.expirarVencidas()).as("nada vencido ainda").isZero();
        relogio.avancar(Duration.ofMinutes(11));
        assertThat(expirador.expirarVencidas()).isEqualTo(1);
        var expirada = json.readTree(get("/api/reservas/" + r2, bruno).getBody());
        assertThat(expirada.get("status").asText()).isEqualTo("EXPIRADA");
        assertThat(lugares.countByEventoIdAndStatus(eventoId, Lugar.Status.LIVRE)).isEqualTo(14);

        // se o pagamento chegar depois de expirada, a saga não vende: a reserva já não está pendente
        assertThat(post("/api/reservas/" + r2 + "/cancelamento", bruno, null).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    @Order(7)
    void circuitBreakerAbreQuandoOGatewayFalha() {
        var breaker = breakers.circuitBreaker(GatewayDePagamentoHttp.NOME);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        for (var k = 0; k < 3; k++) {
            assertThatThrownBy(() -> pagamentos.cobrar(UUID.randomUUID(), BigDecimal.TEN, "tok-5000")).isInstanceOf(PagamentoIndisponivelException.class);
        }
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        var inicio = System.nanoTime();
        assertThatThrownBy(() -> pagamentos.cobrar(UUID.randomUUID(), BigDecimal.TEN, "tok-1234")).isInstanceOf(PagamentoIndisponivelException.class).hasMessageContaining("circuito aberto");
        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).as("falha rápida, sem ir ao gateway").isLessThan(Duration.ofMillis(500));
        assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(1);
    }

    // ---------- apoio ----------

    protected String token(String usuario) {
        var r = post("/api/auth/token", null, Map.of("usuario", usuario, "senha", usuario));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            return json.readTree(r.getBody()).get("token").asText();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    protected Map<String, Object> corpoEvento() {
        return Map.of("nome", "Show de teste", "local", "Arena", "dataHora", Instant.now().plus(Duration.ofDays(30)).toString(),
            "setores", List.of(
                Map.of("nome", "Pista", "preco", 100, "fileiras", 2, "lugaresPorFileira", 5),
                Map.of("nome", "Cadeira", "preco", 250.5, "fileiras", 1, "lugaresPorFileira", 4)));
    }

    protected UUID criarEventoAberto(String organizador) throws Exception {
        var criado = post("/api/eventos", organizador, corpoEvento());
        assertThat(criado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var id = UUID.fromString(json.readTree(criado.getBody()).get("id").asText());
        assertThat(reservar(token("ana"), id, List.of("PISTA-A-1"), "tok-1234", null).getStatusCode()).as("vendas fechadas").isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        var aberto = post("/api/eventos/" + id + "/abrir-vendas", organizador, null);
        assertThat(aberto.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(reservar(token("ana"), id, List.of("NAO-EXISTE"), "tok-1234", null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        return id;
    }

    protected ResponseEntity<String> reservar(String token, UUID eventoId, List<String> lugares, String tokenPagamento, String chaveIdempotencia) {
        var cabecalhos = new HttpHeaders();
        cabecalhos.setBearerAuth(token);
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        if (chaveIdempotencia != null) cabecalhos.set("Idempotency-Key", chaveIdempotencia);
        var corpo = Map.of("eventoId", eventoId, "lugares", lugares, "tokenPagamento", tokenPagamento);
        return http.exchange("/api/reservas", HttpMethod.POST, new HttpEntity<>(corpo, cabecalhos), String.class);
    }

    protected JsonNode esperarStatus(String token, String reservaId, String status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(250)).untilAsserted(() ->
            assertThat(json.readTree(get("/api/reservas/" + reservaId, token).getBody()).get("status").asText()).isEqualTo(status));
        try {
            return json.readTree(get("/api/reservas/" + reservaId, token).getBody());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    protected ResponseEntity<String> get(String caminho, String token) {
        var cabecalhos = new HttpHeaders();
        if (token != null) cabecalhos.setBearerAuth(token);
        return http.exchange(caminho, HttpMethod.GET, new HttpEntity<>(cabecalhos), String.class);
    }

    protected ResponseEntity<String> post(String caminho, String token, Object corpo) {
        var cabecalhos = new HttpHeaders();
        if (token != null) cabecalhos.setBearerAuth(token);
        cabecalhos.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(caminho, HttpMethod.POST, new HttpEntity<>(corpo, cabecalhos), String.class);
    }
}
