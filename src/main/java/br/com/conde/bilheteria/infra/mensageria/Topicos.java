package br.com.conde.bilheteria.infra.mensageria;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.EnableKafkaRetryTopic;
import org.springframework.kafka.config.TopicBuilder;

/** Tópicos criados na subida. Os tópicos de retry e DLT dos consumidores são criados automaticamente. */
@Configuration
@EnableKafka
@EnableKafkaRetryTopic
public class Topicos {

    public static final String RESERVAS = "bilheteria.reservas";

    @Bean
    NewTopic topicoReservas() {
        return TopicBuilder.name(RESERVAS).partitions(3).replicas(1).build();
    }
}
