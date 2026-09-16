package br.com.conde.bilheteria.infra.web;

import br.com.conde.bilheteria.dominio.Excecoes.ConflitoException;
import br.com.conde.bilheteria.dominio.Excecoes.RecursoNaoEncontradoException;
import br.com.conde.bilheteria.dominio.Excecoes.RegraDeNegocioException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/** Erros como ProblemDetail (RFC 9457). Nada de stack trace para o cliente. */
@RestControllerAdvice
public class TratadorDeErros {

    private static final Logger log = LoggerFactory.getLogger(TratadorDeErros.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail validacao(MethodArgumentNotValidException e) {
        var erros = new LinkedHashMap<String, String>();
        e.getBindingResult().getFieldErrors().forEach(f -> erros.merge(f.getField(), f.getDefaultMessage(), (a, b) -> a + "; " + b));
        var p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "dados inválidos");
        p.setProperty("erros", erros);
        return p;
    }

    @ExceptionHandler({ConstraintViolationException.class, HttpMessageNotReadableException.class})
    ProblemDetail requisicaoInvalida(Exception e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e instanceof ConstraintViolationException ? e.getMessage() : "corpo da requisição inválido");
    }

    @ExceptionHandler(RecursoNaoEncontradoException.class)
    ProblemDetail naoEncontrado(RecursoNaoEncontradoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(RegraDeNegocioException.class)
    ProblemDetail regra(RegraDeNegocioException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler({ConflitoException.class, ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ProblemDetail conflito(Exception e) {
        var detalhe = e instanceof ConflitoException ? e.getMessage() : "o recurso foi alterado por outra operação; tente novamente";
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detalhe);
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail interno(Exception e) {
        log.error("erro não tratado", e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "erro interno");
    }

    static Map<String, Object> vazio() {
        return Map.of();
    }
}
