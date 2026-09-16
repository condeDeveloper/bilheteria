package br.com.conde.bilheteria.infra.pagamento;

import br.com.conde.bilheteria.aplicacao.portas.Portas.PagamentoIndisponivelException;
import br.com.conde.bilheteria.aplicacao.portas.Portas.PortaPagamento;
import br.com.conde.bilheteria.aplicacao.portas.Portas.ResultadoPagamento;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.decorators.Decorators;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/**
 * Adaptador HTTP do gateway de pagamento com timeouts curtos, retry e circuit breaker (Resilience4j). Quando o
 * gateway está fora, o breaker abre e as chamadas falham rápido sem esperar o timeout, protegendo o consumidor.
 */
@Component
public class GatewayDePagamentoHttp implements PortaPagamento {

    public static final String NOME = "pagamentos";
    private static final Logger log = LoggerFactory.getLogger(GatewayDePagamentoHttp.class);

    public record Pedido(UUID reservaId, BigDecimal valor, String token) {
    }

    public record Resposta(boolean aprovado, String autorizacao, String motivo) {
    }

    private final RestClient http;
    private final Environment ambiente;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public GatewayDePagamentoHttp(Environment ambiente, CircuitBreakerRegistry breakers, RetryRegistry retries) {
        this.ambiente = ambiente;
        this.breaker = breakers.circuitBreaker(NOME);
        this.retry = retries.retry(NOME);
        var fabrica = ClientHttpRequestFactories.get(ClientHttpRequestFactorySettings.DEFAULTS
            .withConnectTimeout(Duration.ofSeconds(1)).withReadTimeout(Duration.ofSeconds(2)));
        this.http = RestClient.builder().requestFactory(fabrica).build();
    }

    @Override
    public ResultadoPagamento cobrar(UUID reservaId, BigDecimal valor, String tokenPagamento) {
        try {
            var resposta = Decorators.ofSupplier(() -> chamar(new Pedido(reservaId, valor, tokenPagamento)))
                .withRetry(retry)
                .withCircuitBreaker(breaker)
                .get();
            return resposta.aprovado() ? ResultadoPagamento.aprovado(resposta.autorizacao()) : ResultadoPagamento.recusado(resposta.motivo());
        } catch (CallNotPermittedException e) {
            log.warn("circuit breaker '{}' aberto; pagamento da reserva {} adiado", NOME, reservaId);
            throw new PagamentoIndisponivelException("gateway de pagamento indisponível (circuito aberto)", e);
        } catch (ResourceAccessException | HttpServerErrorException e) {
            throw new PagamentoIndisponivelException("gateway de pagamento indisponível: " + e.getMessage(), e);
        }
    }

    private Resposta chamar(Pedido pedido) {
        // a URL é resolvida a cada chamada para funcionar com porta aleatória nos testes
        var url = ambiente.getRequiredProperty("bilheteria.pagamentos.url");
        var resposta = http.post().uri(url).body(pedido).retrieve().body(Resposta.class);
        if (resposta == null) throw new ResourceAccessException("resposta vazia do gateway");
        return resposta;
    }
}
