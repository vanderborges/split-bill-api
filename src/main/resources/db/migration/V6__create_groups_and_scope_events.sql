create table groups (
    id uuid primary key,
    name varchar(160) not null,
    description varchar(500),
    created_by_user_id uuid not null references app_users(id),
    active boolean not null default true,
    created_at timestamp not null,
    updated_at timestamp not null
);

create table group_members (
    id uuid primary key,
    group_id uuid not null references groups(id),
    user_id uuid not null references app_users(id),
    role varchar(20) not null,
    active boolean not null default true,
    created_at timestamp not null,
    updated_at timestamp not null,
    unique (group_id, user_id)
);

insert into groups (id, name, description, created_by_user_id, active, created_at, updated_at)
select
    '00000000-0000-0000-0000-000000000100',
    'Grupo principal',
    'Grupo criado automaticamente para dados existentes',
    id,
    true,
    now(),
    now()
from app_users
where deleted_at is null
order by created_at, id
limit 1;

insert into group_members (id, group_id, user_id, role, active, created_at, updated_at)
select
    md5(random()::text || clock_timestamp()::text)::uuid,
    '00000000-0000-0000-0000-000000000100',
    id,
    case when admin and admin_position <= 2 then 'ADMIN' else 'MEMBER' end,
    active,
    now(),
    now()
from (
    select
        id,
        admin,
        active,
        case
            when admin then row_number() over (partition by admin order by created_at, id)
            else null
        end as admin_position
    from app_users
    where deleted_at is null
) users_to_import
where exists (
    select 1
    from groups
    where id = '00000000-0000-0000-0000-000000000100'
);

alter table events add column group_id uuid references groups(id);
update events
set group_id = '00000000-0000-0000-0000-000000000100'
where group_id is null
  and exists (
      select 1
      from groups
      where id = '00000000-0000-0000-0000-000000000100'
  );
do $$
begin
    if exists (select 1 from events where group_id is null) then
        raise exception 'Cannot assign existing events to a group because there are no users to create the default group';
    end if;

    alter table events alter column group_id set not null;
end $$;
alter table events add column deleted_at timestamp;

drop index if exists ux_events_monthly_month;

create unique index ux_events_monthly_group_month
    on events(group_id, month_id)
    where type = 'MONTHLY';
