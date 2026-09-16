package br.com.conde.bilheteria;

import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

/** Cenário completo com H2 em modo PostgreSQL e Kafka embutido: roda em qualquer máquina, sem Docker. */
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 3, kraft = true)
class FluxoDeCompraTest extends CenarioDeCompra {
}
