package br.com.conde.bilheteria.infra.web;

import br.com.conde.bilheteria.aplicacao.ServicoEventos;
import br.com.conde.bilheteria.aplicacao.ServicoEventos.Disponibilidade;
import br.com.conde.bilheteria.aplicacao.ServicoEventos.NovoEvento;
import br.com.conde.bilheteria.aplicacao.ServicoEventos.NovoSetor;
import br.com.conde.bilheteria.aplicacao.ServicoIngressos;
import br.com.conde.bilheteria.aplicacao.ServicoReservas;
import br.com.conde.bilheteria.aplicacao.ServicoReservas.NovaReserva;
import br.com.conde.bilheteria.dominio.Excecoes.RecursoNaoEncontradoException;
import br.com.conde.bilheteria.infra.web.Dtos.EventoRequest;
import br.com.conde.bilheteria.infra.web.Dtos.EventoResponse;
import br.com.conde.bilheteria.infra.web.Dtos.IngressoResponse;
import br.com.conde.bilheteria.infra.web.Dtos.ReservaRequest;
import br.com.conde.bilheteria.infra.web.Dtos.ReservaResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

public final class Controladores {

    private Controladores() {
    }

    @RestController
    @RequestMapping("/api/eventos")
    @Tag(name = "Eventos")
    public static class Eventos {

        private final ServicoEventos servico;

        public Eventos(ServicoEventos servico) {
            this.servico = servico;
        }

        @PostMapping
        @Operation(summary = "Cria um evento com setores e gera os lugares (ORGANIZADOR)", security = @SecurityRequirement(name = "bearer"))
        public ResponseEntity<EventoResponse> criar(@Valid @RequestBody EventoRequest req) {
            var evento = servico.criar(new NovoEvento(req.nome(), req.local(), req.dataHora(),
                req.setores().stream().map(s -> new NovoSetor(s.nome(), s.preco(), s.fileiras(), s.lugaresPorFileira())).toList()));
            return ResponseEntity.created(URI.create("/api/eventos/" + evento.getId())).body(EventoResponse.de(evento));
        }

        @PostMapping("/{id}/abrir-vendas")
        @Operation(summary = "Abre as vendas do evento (ORGANIZADOR)", security = @SecurityRequirement(name = "bearer"))
        public EventoResponse abrirVendas(@PathVariable UUID id) {
            return EventoResponse.de(servico.abrirVendas(id));
        }

        @GetMapping("/{id}")
        @Operation(summary = "Detalhe do evento")
        public EventoResponse obter(@PathVariable UUID id) {
            return EventoResponse.de(servico.obter(id));
        }

        @GetMapping("/{id}/disponibilidade")
        @Operation(summary = "Lugares livres por setor (em cache por alguns segundos)")
        public Disponibilidade disponibilidade(@PathVariable UUID id) {
            return servico.disponibilidade(id);
        }
    }

    @RestController
    @RequestMapping("/api/reservas")
    @Tag(name = "Reservas")
    @Validated
    public static class Reservas {

        private final ServicoReservas servico;
        private final ServicoIngressos ingressos;

        public Reservas(ServicoReservas servico, ServicoIngressos ingressos) {
            this.servico = servico;
            this.ingressos = ingressos;
        }

        @PostMapping
        @Operation(summary = "Reserva lugares e inicia a saga de compra. Envie Idempotency-Key para repetir com segurança",
            security = @SecurityRequirement(name = "bearer"))
        public ResponseEntity<ReservaResponse> criar(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 100) String chave,
                                                     @Valid @RequestBody ReservaRequest req) {
            var criacao = servico.criar(jwt.getSubject(), chave, new NovaReserva(req.eventoId(), req.lugares(), req.tokenPagamento()));
            var corpo = ReservaResponse.de(criacao.reserva());
            return criacao.repetida()
                ? ResponseEntity.ok(corpo)
                : ResponseEntity.created(URI.create("/api/reservas/" + corpo.id())).body(corpo);
        }

        @GetMapping
        @Operation(summary = "Reservas do cliente autenticado", security = @SecurityRequirement(name = "bearer"))
        public List<ReservaResponse> minhas(@AuthenticationPrincipal Jwt jwt) {
            return servico.doCliente(jwt.getSubject()).stream().map(ReservaResponse::de).toList();
        }

        @GetMapping("/{id}")
        @Operation(summary = "Detalhe de uma reserva do cliente", security = @SecurityRequirement(name = "bearer"))
        public ReservaResponse obter(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
            return servico.obterDoCliente(id, jwt.getSubject()).map(ReservaResponse::de)
                .orElseThrow(() -> new RecursoNaoEncontradoException("reserva", id));
        }

        @PostMapping("/{id}/cancelamento")
        @Operation(summary = "Cancela uma reserva pendente e devolve os lugares", security = @SecurityRequirement(name = "bearer"))
        public ReservaResponse cancelar(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
            return ReservaResponse.de(servico.cancelar(id, jwt.getSubject()));
        }

        @GetMapping("/{id}/ingressos")
        @Operation(summary = "Ingressos emitidos para a reserva", security = @SecurityRequirement(name = "bearer"))
        public List<IngressoResponse> ingressos(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
            servico.obterDoCliente(id, jwt.getSubject()).orElseThrow(() -> new RecursoNaoEncontradoException("reserva", id));
            return ingressos.daReserva(id).stream().map(IngressoResponse::de).toList();
        }
    }

    @RestController
    @RequestMapping("/api/ingressos")
    @Tag(name = "Ingressos")
    public static class Ingressos {

        private final ServicoIngressos servico;

        public Ingressos(ServicoIngressos servico) {
            this.servico = servico;
        }

        @PostMapping("/{codigo}/check-in")
        @Operation(summary = "Portaria: valida a assinatura e registra a entrada (ORGANIZADOR)", security = @SecurityRequirement(name = "bearer"))
        public ResponseEntity<IngressoResponse> checkIn(@PathVariable String codigo) {
            return ResponseEntity.status(HttpStatus.OK).body(IngressoResponse.de(servico.checkIn(codigo)));
        }
    }
}
