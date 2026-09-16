package br.com.conde.bilheteria.dominio;

/** Exceções do domínio. A camada web as converte em ProblemDetail com o status adequado. */
public final class Excecoes {

    private Excecoes() {
    }

    /** Violação de regra de negócio: 422. */
    public static class RegraDeNegocioException extends RuntimeException {
        public RegraDeNegocioException(String mensagem) {
            super(mensagem);
        }
    }

    /** Recurso inexistente: 404. */
    public static class RecursoNaoEncontradoException extends RuntimeException {
        public RecursoNaoEncontradoException(String recurso, Object id) {
            super(recurso + " '" + id + "' não encontrado");
        }
    }

    /** Disputa perdida por um recurso (lugar já reservado, chave de idempotência em uso): 409. */
    public static class ConflitoException extends RuntimeException {
        public ConflitoException(String mensagem) {
            super(mensagem);
        }
    }
}
