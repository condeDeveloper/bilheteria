# Bilheteria

[![CI](https://github.com/condeDeveloper/bilheteria/actions/workflows/ci.yml/badge.svg)](https://github.com/condeDeveloper/bilheteria/actions/workflows/ci.yml)

Venda de ingressos em Java 21 e Spring Boot 3, construída em volta do problema que toda entrevista de back-end pergunta: **como não vender o mesmo lugar duas vezes** quando milhares de pessoas clicam ao mesmo tempo. E do que vem depois: cobrar, compensar quando o pagamento falha, emitir ingressos assinados, expirar reservas abandonadas, e fazer tudo isso sobrevivendo a broker fora do ar e mensagens duplicadas.

## Sobe com um comando

```bash
docker compose up --build
```

| Serviço | Endereço | Para quê |
|---|---|---|
| API (2 réplicas) | http://localhost:8080/docs | Swagger com autenticação Bearer |
| Kafka UI | http://localhost:8090 | tópicos, grupos de consumo, retry e DLT |
| Jaeger | http://localhost:16686 | traces de cada requisição, incluindo o SQL |
| Prometheus | http://localhost:9090 | métricas brutas |
| Grafana | http://localhost:3000 (admin / admin) | dashboard "Bilheteria" provisionado |

Fluxo completo:

```bash
ORG=$(curl -s localhost:8080/api/auth/token -H 'Content-Type: application/json' -d '{"usuario":"organizador","senha":"organizador"}' | jq -r .token)
ANA=$(curl -s localhost:8080/api/auth/token -H 'Content-Type: application/json' -d '{"usuario":"ana","senha":"ana"}' | jq -r .token)

EVENTO=$(curl -s localhost:8080/api/eventos -H "Authorization: Bearer $ORG" -H 'Content-Type: application/json' -d '{
  "nome":"Show","local":"Arena","dataHora":"2027-01-10T21:00:00Z",
  "setores":[{"nome":"Pista","preco":100,"fileiras":10,"lugaresPorFileira":50},{"nome":"Cadeira","preco":250,"fileiras":5,"lugaresPorFileira":40}]}' | jq -r .id)
curl -s -X POST localhost:8080/api/eventos/$EVENTO/abrir-vendas -H "Authorization: Bearer $ORG" > /dev/null

RESERVA=$(curl -s localhost:8080/api/reservas -H "Authorization: Bearer $ANA" -H 'Content-Type: application/json' -H "Idempotency-Key: pedido-42" \
  -d "{\"eventoId\":\"$EVENTO\",\"lugares\":[\"PISTA-A-1\",\"PISTA-A-2\"],\"tokenPagamento\":\"tok-1234\"}" | jq -r .id)

sleep 3   # outbox → Kafka → saga cobra → confirma → emissão de ingressos
curl -s localhost:8080/api/reservas/$RESERVA -H "Authorization: Bearer $ANA" | jq '{status, valorTotal}'
curl -s localhost:8080/api/reservas/$RESERVA/ingressos -H "Authorization: Bearer $ANA" | jq '.[0].codigo'
```

Tokens de pagamento no gateway simulado: termina em `0000` recusa, `9999` demora 3 s (estoura o timeout), `5000` devolve erro 500, qualquer outro aprova.

## Como funciona

```
 POST /api/reservas ──▶ ServicoReservas ── SELECT ... FOR UPDATE nos lugares (ordem fixa) ──▶ Reserva PENDENTE
                                          │ mesma transação: outbox ReservaCriada, chave de idempotência
                                          ▼
                         RelayOutbox (@Scheduled) ──▶ Kafka  bilheteria.reservas  (chave = id da reserva)
                                                          │                     │
                                          grupo saga-compra                grupo emissao-ingressos
                                                          ▼                     ▼
                                    SagaDeCompra: cobra no gateway      ServicoIngressos: emite códigos HMAC
                                    (Resilience4j: retry + breaker)     quando chega ReservaConfirmada
                                      aprovado → CONFIRMADA, lugares VENDIDOS
                                      recusado → CANCELADA, lugares LIVRES (compensação)
                                      indisponível → exceção → .retry → .retry → .dlt
                         AgendadorDeExpiracao (lock no Redis) ──▶ PENDENTE vencida → EXPIRADA, lugares LIVRES
```

- **Concorrência**: os lugares são lidos com `PESSIMISTIC_WRITE` em ordem de código (sem deadlock); quem chega segundo vê o lugar ocupado e recebe 409. Toda entidade tem `@Version` como segunda linha de defesa. O teste dispara 24 requisições simultâneas pelo mesmo lugar e exige exatamente uma vencedora.
- **Outbox transacional**: o evento é gravado na mesma transação do agregado. O relay publica em ordem com a chave do agregado, então os eventos de uma reserva chegam em ordem na mesma partição. Entrega pelo menos uma vez.
- **Consumidores idempotentes**: tabela inbox `mensagens_processadas` por consumidor; a mesma mensagem duas vezes é no-op. `@RetryableTopic` com backoff exponencial e dead letter topic.
- **Saga com compensação**: a chamada ao gateway fica **fora** da transação de banco (não segura locks esperando rede). Pagamento recusado devolve os lugares; pagamento aprovado depois do prazo também, marcando estorno.
- **Idempotência HTTP**: `Idempotency-Key` guarda a reserva criada; repetir devolve 200 com a mesma reserva em vez de 409 por lugar ocupado. Corrida entre duas requisições com a mesma chave é resolvida pela chave primária.
- **Resiliência**: `RestClient` com timeouts curtos, retry de 2 tentativas e circuit breaker (abre com 50% de falha em 6 chamadas, meio-aberto após 10 s). Com o breaker aberto, a saga falha em milissegundos e a mensagem vai para retry, sem esgotar threads.
- **Ingressos assinados**: código = `reserva.lugar.hmac` em Base64 URL-safe; a portaria valida a assinatura antes de consultar o banco, e o check-in é único.
- **Redis**: cache da disponibilidade (TTL 5 s, invalidado a cada reserva) e lock distribuído `SET NX PX` + Lua para o agendador rodar em uma réplica só.
- **Observabilidade**: Actuator com liveness e readiness, Micrometer para Prometheus (HTTP, JVM, Kafka, breaker, métricas do outbox), tracing OpenTelemetry para o Jaeger com `traceId` nos logs JSON (Logstash encoder).
- **Java 21**: records, `sealed interface` para os eventos (o switch dos consumidores é exaustivo), pattern matching, virtual threads ligadas no Tomcat.

## O que tem aqui, na linguagem das vagas

| Pedem | Onde está |
|---|---|
| Java 21, Spring Boot 3 | records, sealed, virtual threads, Spring 6 |
| Arquitetura em camadas / hexagonal, DDD | `dominio` (agregados ricos, eventos), `aplicacao` (casos de uso e portas), `infra` (adaptadores); regras verificadas por **ArchUnit** |
| JPA / Hibernate, PostgreSQL, Flyway | entidades com `@Version`, lock pessimista, migration V1, `ddl-auto: validate` |
| Kafka | producer idempotente, consumer groups, `@RetryableTopic`, DLT, partição por chave |
| Outbox, inbox, idempotência | `outbox`, `mensagens_processadas`, `chaves_idempotencia` |
| Saga / compensação | `SagaDeCompra` com `TransactionTemplate` |
| Resilience4j | circuit breaker e retry programáticos com `Decorators`, métricas e health |
| Redis | cache com TTL e lock distribuído com script Lua |
| Spring Security, JWT | resource server OAuth2 com HS256, papéis ORGANIZADOR e CLIENTE |
| Bean Validation, ProblemDetail | DTOs validados, erros RFC 9457 com 400/404/409/422 |
| Testes | JUnit 5, AssertJ, Awaitility, `@SpringBootTest` com `TestRestTemplate`, Kafka embutido, **Testcontainers** (PostgreSQL, Kafka, Redis), teste de concorrência real |
| Docker | Dockerfile multi-stage com **layered jar**, usuário sem privilégio, healthcheck; compose com 8 serviços e 2 réplicas |
| CI/CD | GitHub Actions: testes rápidos, testes de integração com Testcontainers, build e push da imagem para o GHCR |
| Observabilidade | Actuator, Micrometer, Prometheus, Grafana, OpenTelemetry, Jaeger, logs JSON |

## Rodar sem Docker

```bash
./mvnw test                          # H2 em modo PostgreSQL + Kafka embutido: 18 testes, ~1 min
./mvnw verify -Pintegracao           # com Docker: mesmo cenário em PostgreSQL, Kafka e Redis reais (Testcontainers)
```

Para subir a aplicação localmente é preciso PostgreSQL, Kafka e Redis (o compose é o caminho mais curto).

## Endpoints

| Método | Rota | Papel | Descrição |
|---|---|---|---|
| POST | `/api/auth/token` | | JWT para `organizador`, `ana` ou `bruno` |
| POST | `/api/eventos` | ORGANIZADOR | cria evento e gera lugares por setor |
| POST | `/api/eventos/{id}/abrir-vendas` | ORGANIZADOR | libera as reservas |
| GET | `/api/eventos/{id}` `/disponibilidade` | | detalhe e lugares livres por setor (cache) |
| POST | `/api/reservas` | CLIENTE | reserva lugares; `Idempotency-Key` opcional |
| GET | `/api/reservas` `/{id}` `/{id}/ingressos` | CLIENTE | acompanhamento |
| POST | `/api/reservas/{id}/cancelamento` | CLIENTE | cancela pendente e devolve lugares |
| POST | `/api/ingressos/{codigo}/check-in` | ORGANIZADOR | portaria |
| GET | `/actuator/health/liveness` `/readiness` `/prometheus` | | operação |

## Estrutura

```
src/main/java/br/com/conde/bilheteria
  dominio/        Evento, Setor, Lugar, Reserva, Ingresso, EventoDeDominio (sealed), exceções
  aplicacao/      ServicoEventos, ServicoReservas, SagaDeCompra, ServicoIngressos
    portas/       PortaPagamento, PortaBloqueio, repositórios Spring Data
    suporte/      Outbox (registro, inbox, (des)serialização), MensagemOutbox, ChaveIdempotencia, AssinadorDeIngressos
  infra/
    mensageria/   RelayOutbox, Consumidores (@RetryableTopic + DLT), Topicos, Envelope
    pagamento/    GatewayDePagamentoHttp (Resilience4j), SimuladorDeGatewayController
    redis/        BloqueioRedis (SET NX + Lua), BloqueioEmMemoria (testes)
    agendamento/  AgendadorDeExpiracao
    seguranca/    SegurancaConfig (JWT HS256), AutenticacaoController
    web/          Controladores, Dtos, TratadorDeErros
src/main/resources/db/migration/V1__esquema_inicial.sql
src/test/java   DominioTest, ArquiteturaTest, CenarioDeCompra (7 cenários) → FluxoDeCompraTest (H2) e FluxoDeCompraIT (Testcontainers)
infra/          prometheus.yml, provisionamento do Grafana
Dockerfile, docker-compose.yml, .github/workflows/ci.yml
```

## Licença

MIT
