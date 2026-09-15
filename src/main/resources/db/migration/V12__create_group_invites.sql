create table group_invites (
    id uuid primary key,
    group_id uuid not null references groups(id),
    created_by_user_id uuid not null references app_users(id),
    active boolean not null default true,
    created_at timestamp not null
);

create index idx_group_invites_group_active on group_invites(group_id) where active = true;
