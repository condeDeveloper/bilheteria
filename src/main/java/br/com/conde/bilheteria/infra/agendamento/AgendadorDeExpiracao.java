package br.com.conde.bilheteria.infra.agendamento;

import br.com.conde.bilheteria.aplicacao.ServicoReservas;
import br.com.conde.bilheteria.aplicacao.portas.Portas.PortaBloqueio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Expira reservas pendentes vencidas, devolvendo os lugares. Só uma instância roda por vez, graças ao lock
 * distribuído; cada reserva é uma transação própria, então uma falha não derruba o lote.
 */
@Component
public class AgendadorDeExpiracao {

    public static final String CHAVE = "expiracao-de-reservas";
    private static final Logger log = LoggerFactory.getLogger(AgendadorDeExpiracao.class);

    private final ServicoReservas reservas;
    private final PortaBloqueio bloqueio;

    public AgendadorDeExpiracao(ServicoReservas reservas, PortaBloqueio bloqueio) {
        this.reservas = reservas;
        this.bloqueio = bloqueio;
    }

    @Scheduled(fixedDelayString = "${bilheteria.expiracao.intervalo:15000}")
    public void ciclo() {
        if (!bloqueio.tentarAdquirir(CHAVE, Duration.ofSeconds(30))) return;
        try {
            var n = expirarVencidas();
            if (n > 0) log.info("{} reserva(s) expirada(s)", n);
        } finally {
            bloqueio.liberar(CHAVE);
        }
    }

    public int expirarVencidas() {
        var total = 0;
        for (var id : reservas.pendentesVencidas(200)) {
            try {
                if (reservas.expirar(id)) total++;
            } catch (Exception e) {
                log.warn("falha ao expirar reserva {}: {}", id, e.getMessage());
            }
        }
        return total;
    }
}
