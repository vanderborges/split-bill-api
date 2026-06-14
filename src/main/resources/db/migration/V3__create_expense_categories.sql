create table expense_categories (
    id uuid primary key,
    name varchar(80) not null unique,
    active boolean not null default true,
    created_at timestamp not null
);

insert into expense_categories (id, name, active, created_at) values
    (md5(random()::text || clock_timestamp()::text)::uuid, 'Alimentacao', true, now()),
    (md5(random()::text || clock_timestamp()::text)::uuid, 'Bebidas', true, now()),
    (md5(random()::text || clock_timestamp()::text)::uuid, 'Transporte', true, now()),
    (md5(random()::text || clock_timestamp()::text)::uuid, 'Hospedagem', true, now()),
    (md5(random()::text || clock_timestamp()::text)::uuid, 'Mercado', true, now()),
    (md5(random()::text || clock_timestamp()::text)::uuid, 'Lazer', true, now()),
    (md5(random()::text || clock_timestamp()::text)::uuid, 'Geral', true, now());
