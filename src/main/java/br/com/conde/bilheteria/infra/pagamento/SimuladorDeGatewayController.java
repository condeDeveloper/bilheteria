package br.com.conde.bilheteria.infra.pagamento;

import br.com.conde.bilheteria.infra.pagamento.GatewayDePagamentoHttp.Pedido;
import br.com.conde.bilheteria.infra.pagamento.GatewayDePagamentoHttp.Resposta;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Faz o papel do gateway externo, para a saga ter algo real para chamar por HTTP. O comportamento depende do fim
 * do token: 0000 recusa, 9999 demora (estoura o timeout do cliente), 5000 devolve erro 500, qualquer outro aprova.
 */
@RestController
@RequestMapping("/simulador/pagamentos")
@Tag(name = "Simulador", description = "Gateway de pagamento simulado")
public class SimuladorDeGatewayController {

    @PostMapping
    @Operation(summary = "Cobra um token: termina em 0000 recusa, 9999 demora 3 s, 5000 erro 500, senão aprova")
    public ResponseEntity<Resposta> cobrar(@RequestBody Pedido pedido) throws InterruptedException {
        var token = pedido.token() == null ? "" : pedido.token();
        if (token.endsWith("9999")) Thread.sleep(3_000);
        if (token.endsWith("5000")) return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        if (token.endsWith("0000")) return ResponseEntity.ok(new Resposta(false, null, "cartão recusado pelo emissor"));
        return ResponseEntity.ok(new Resposta(true, "AUT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(), null));
    }
}
