package br.com.conde.bilheteria.aplicacao.portas;

import br.com.conde.bilheteria.aplicacao.suporte.ChaveIdempotencia;
import br.com.conde.bilheteria.aplicacao.suporte.MensagemOutbox;
import br.com.conde.bilheteria.aplicacao.suporte.MensagemProcessada;
import br.com.conde.bilheteria.dominio.Evento;
import br.com.conde.bilheteria.dominio.Ingresso;
import br.com.conde.bilheteria.dominio.Lugar;
import br.com.conde.bilheteria.dominio.Reserva;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repositórios Spring Data. São portas: a aplicação declara o que precisa e o Spring gera a implementação. */
public final class Repositorios {

    private Repositorios() {
    }

    public interface RepositorioEventos extends JpaRepository<Evento, UUID> {
    }

    public interface RepositorioLugares extends JpaRepository<Lugar, UUID> {

        /**
         * Lê os lugares pedidos com lock de escrita (SELECT ... FOR UPDATE), em ordem determinística de código para que
         * duas transações disputando os mesmos lugares adquiram os locks na mesma ordem e não entrem em deadlock.
         */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select l from Lugar l where l.evento.id = :eventoId and l.codigo in :codigos order by l.codigo")
        List<Lugar> bloquearPorCodigos(@Param("eventoId") UUID eventoId, @Param("codigos") Collection<String> codigos);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select l from Lugar l where l.id in :ids order by l.codigo")
        List<Lugar> bloquearPorIds(@Param("ids") Collection<UUID> ids);

        List<Lugar> findByEventoIdOrderByCodigo(UUID eventoId);

        long countByEventoIdAndStatus(UUID eventoId, Lugar.Status status);
    }

    public interface RepositorioReservas extends JpaRepository<Reserva, UUID> {
        List<Reserva> findByStatusAndExpiraEmBefore(Reserva.Status status, Instant limite, Pageable pagina);

        List<Reserva> findByClienteIdOrderByCriadaEmDesc(String clienteId);
    }

    public interface RepositorioIngressos extends JpaRepository<Ingresso, UUID> {
        List<Ingresso> findByReservaIdOrderByEmitidoEm(UUID reservaId);

        Optional<Ingresso> findByCodigo(String codigo);

        boolean existsByReservaId(UUID reservaId);
    }

    public interface RepositorioOutbox extends JpaRepository<MensagemOutbox, UUID> {
        List<MensagemOutbox> findByPublicadoEmIsNullAndTentativasLessThanOrderByCriadoEmAsc(int maximoDeTentativas, Pageable pagina);

        long countByPublicadoEmIsNull();
    }

    public interface RepositorioMensagensProcessadas extends JpaRepository<MensagemProcessada, MensagemProcessada.Chave> {
    }

    public interface RepositorioChavesIdempotencia extends JpaRepository<ChaveIdempotencia, String> {
    }
}
