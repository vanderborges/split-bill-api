create table events (
    id uuid primary key,
    name varchar(160) not null,
    description varchar(500),
    type varchar(30) not null,
    status varchar(20) not null,
    month_id uuid references months(id),
    created_at timestamp not null,
    closed_at timestamp
);

create unique index ux_events_monthly_month
    on events(month_id)
    where type = 'MONTHLY';

insert into events (id, name, description, type, status, month_id, created_at, closed_at)
select
    md5(random()::text || clock_timestamp()::text)::uuid,
    lpad(month::text, 2, '0') || '/' || year::text,
    'Evento mensal criado automaticamente',
    'MONTHLY',
    status,
    id,
    created_at,
    closed_at
from months;

alter table expenses add column event_id uuid references events(id);
alter table expenses add column source_event_id uuid references events(id);
alter table expenses add column installment_group_id uuid;
alter table expenses add column installment_number integer;
alter table expenses add column total_installments integer;
alter table expenses alter column month_id drop not null;

update expenses e
set event_id = ev.id
from events ev
where ev.month_id = e.month_id
  and ev.type = 'MONTHLY';

alter table expenses alter column event_id set not null;

create table installment_groups (
    id uuid primary key,
    description varchar(255) not null,
    total_amount numeric(15, 2) not null check (total_amount > 0),
    total_installments integer not null check (total_installments > 1),
    first_event_id uuid not null references events(id),
    payer_id uuid not null references app_users(id),
    category varchar(80) not null,
    created_at timestamp not null,
    cancelled_at timestamp
);

alter table expenses
    add constraint fk_expenses_installment_group
    foreign key (installment_group_id) references installment_groups(id);

create table event_settlements (
    id uuid primary key,
    event_id uuid not null references events(id),
    user_id uuid not null references app_users(id),
    role varchar(20) not null,
    amount numeric(15, 2) not null check (amount >= 0),
    status varchar(40) not null,
    updated_by_admin_id uuid references app_users(id),
    updated_at timestamp not null,
    created_at timestamp not null,
    unique (event_id, user_id)
);
