-- Aparelhos (token do Firebase Cloud Messaging) de cada usuario, pra
-- mandar push quando uma notificacao e criada. Um token pertence a um
-- aparelho/instalacao: se outra conta logar no mesmo aparelho, o token
-- passa pra ela.
create table device_tokens (
    id uuid primary key,
    user_id uuid not null references app_users(id) on delete cascade,
    token varchar(512) not null unique,
    platform varchar(20) not null,
    created_at timestamp not null,
    updated_at timestamp not null
);

create index ix_device_tokens_user on device_tokens(user_id);
