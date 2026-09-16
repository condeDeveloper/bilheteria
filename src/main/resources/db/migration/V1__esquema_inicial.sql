-- Esquema da bilheteria. Compatível com PostgreSQL e com H2 em modo PostgreSQL (testes locais).

create table eventos (
    id            uuid primary key,
    nome          varchar(120)             not null,
    local         varchar(120)             not null,
    data_hora     timestamp with time zone not null,
    status        varchar(30)              not null,
    criado_em     timestamp with time zone not null,
    versao        bigint                   not null
);

create table setores (
    id            uuid primary key,
    evento_id     uuid          not null references eventos (id),
    nome          varchar(60)   not null,
    preco         numeric(12,2) not null,
    capacidade    integer       not null,
    constraint uk_setor_nome unique (evento_id, nome)
);

create table lugares (
    id            uuid primary key,
    evento_id     uuid        not null references eventos (id),
    setor_id      uuid        not null references setores (id),
    codigo        varchar(20) not null,
    status        varchar(30) not null,
    reserva_id    uuid,
    versao        bigint      not null,
    constraint uk_lugar_codigo unique (evento_id, codigo)
);
create index ix_lugares_evento_status on lugares (evento_id, status);

create table reservas (
    id              uuid primary key,
    evento_id       uuid                     not null references eventos (id),
    cliente_id      varchar(64)              not null,
    status          varchar(30)              not null,
    valor_total     numeric(12,2)            not null,
    token_pagamento varchar(64)              not null,
    motivo          varchar(200),
    criada_em       timestamp with time zone not null,
    expira_em       timestamp with time zone not null,
    atualizada_em   timestamp with time zone not null,
    versao          bigint                   not null
);
create index ix_reservas_status_expira on reservas (status, expira_em);
create index ix_reservas_cliente on reservas (cliente_id);

-- lugares presos por cada reserva
create table reserva_lugares (
    reserva_id    uuid not null references reservas (id),
    lugar_id      uuid not null references lugares (id),
    primary key (reserva_id, lugar_id)
);

create table ingressos (
    id            uuid primary key,
    reserva_id    uuid                     not null references reservas (id),
    lugar_id      uuid                     not null references lugares (id),
    codigo        varchar(200)             not null unique,
    status        varchar(30)              not null,
    emitido_em    timestamp with time zone not null,
    utilizado_em  timestamp with time zone
);
create index ix_ingressos_reserva on ingressos (reserva_id);

-- outbox: eventos de domínio gravados na mesma transação do agregado e publicados depois no Kafka
create table outbox (
    id            uuid primary key,
    tipo          varchar(60)              not null,
    agregado_id   uuid                     not null,
    corpo         text                     not null,
    criado_em     timestamp with time zone not null,
    publicado_em  timestamp with time zone,
    tentativas    integer                  not null default 0,
    ultimo_erro   varchar(1000)
);
create index ix_outbox_pendentes on outbox (publicado_em, criado_em);

-- inbox: ids de mensagens já processadas por cada consumidor, para idempotência
create table mensagens_processadas (
    mensagem_id   uuid                     not null,
    consumidor    varchar(60)              not null,
    processada_em timestamp with time zone not null,
    primary key (mensagem_id, consumidor)
);

-- chaves de idempotência das requisições de criação de reserva
create table chaves_idempotencia (
    chave         varchar(100)             primary key,
    cliente_id    varchar(64)              not null,
    reserva_id    uuid                     not null,
    criada_em     timestamp with time zone not null
);
