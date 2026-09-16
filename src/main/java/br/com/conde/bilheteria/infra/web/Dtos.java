package br.com.conde.bilheteria.infra.web;

import br.com.conde.bilheteria.dominio.Evento;
import br.com.conde.bilheteria.dominio.Ingresso;
import br.com.conde.bilheteria.dominio.Reserva;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Contratos da API. Validação declarativa na borda; as regras de negócio ficam no domínio. */
public final class Dtos {

    private Dtos() {
    }

    public record SetorRequest(@NotBlank @Size(max = 60) String nome, @NotNull @PositiveOrZero BigDecimal preco,
                               @Min(1) @Max(26) int fileiras, @Min(1) @Max(200) int lugaresPorFileira) {
    }

    public record EventoRequest(@NotBlank @Size(max = 120) String nome, @Size(max = 120) String local, @NotNull @Future Instant dataHora,
                                @NotEmpty @Valid List<SetorRequest> setores) {
    }

    public record SetorResponse(UUID id, String nome, BigDecimal preco, int capacidade) {
    }

    public record EventoResponse(UUID id, String nome, String local, Instant dataHora, Evento.Status status, List<SetorResponse> setores) {
        public static EventoResponse de(Evento e) {
            return new EventoResponse(e.getId(), e.getNome(), e.getLocal(), e.getDataHora(), e.getStatus(),
                e.getSetores().stream().map(s -> new SetorResponse(s.getId(), s.getNome(), s.getPreco(), s.getCapacidade())).toList());
        }
    }

    public record ReservaRequest(@NotNull UUID eventoId, @NotEmpty @Size(max = Reserva.MAXIMO_DE_LUGARES) List<@NotBlank String> lugares,
                                 @NotBlank @Size(max = 64) String tokenPagamento) {
    }

    public record ReservaResponse(UUID id, UUID eventoId, String clienteId, Reserva.Status status, BigDecimal valorTotal, List<UUID> lugares,
                                  String motivo, Instant criadaEm, Instant expiraEm, Instant atualizadaEm, long versao) {
        public static ReservaResponse de(Reserva r) {
            return new ReservaResponse(r.getId(), r.getEventoId(), r.getClienteId(), r.getStatus(), r.getValorTotal(), r.getLugares(),
                r.getMotivo(), r.getCriadaEm(), r.getExpiraEm(), r.getAtualizadaEm(), r.getVersao());
        }
    }

    public record IngressoResponse(UUID id, UUID reservaId, UUID lugarId, String codigo, Ingresso.Status status, Instant emitidoEm, Instant utilizadoEm) {
        public static IngressoResponse de(Ingresso i) {
            return new IngressoResponse(i.getId(), i.getReservaId(), i.getLugarId(), i.getCodigo(), i.getStatus(), i.getEmitidoEm(), i.getUtilizadoEm());
        }
    }
}
