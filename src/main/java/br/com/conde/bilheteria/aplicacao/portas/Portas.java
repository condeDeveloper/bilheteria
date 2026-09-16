package br.com.conde.bilheteria.aplicacao.portas;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

/** Portas para o mundo externo. A aplicação depende destas interfaces; a infraestrutura as implementa. */
public final class Portas {

    private Portas() {
    }

    /** Gateway de pagamento. Recusa é um resultado; indisponibilidade é exceção (e dispara retry na saga). */
    public interface PortaPagamento {
        ResultadoPagamento cobrar(UUID reservaId, BigDecimal valor, String tokenPagamento);
    }

    public record ResultadoPagamento(boolean aprovado, String autorizacao, String motivo) {
        public static ResultadoPagamento aprovado(String autorizacao) {
            return new ResultadoPagamento(true, autorizacao, null);
        }

        public static ResultadoPagamento recusado(String motivo) {
            return new ResultadoPagamento(false, null, motivo);
        }
    }

    public static class PagamentoIndisponivelException extends RuntimeException {
        public PagamentoIndisponivelException(String mensagem, Throwable causa) {
            super(mensagem, causa);
        }
    }

    /** Bloqueio distribuído para tarefas que só uma instância deve executar por vez (expiração de reservas). */
    public interface PortaBloqueio {
        boolean tentarAdquirir(String chave, Duration duracao);

        void liberar(String chave);
    }
}
