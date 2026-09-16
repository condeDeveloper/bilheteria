package br.com.conde.bilheteria;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableCaching
@EnableScheduling
@EnableJpaRepositories(considerNestedRepositories = true) // os repositórios são interfaces aninhadas em Repositorios
public class BilheteriaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BilheteriaApplication.class, args);
    }
}
