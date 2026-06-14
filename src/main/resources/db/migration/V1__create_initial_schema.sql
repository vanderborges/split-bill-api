create table app_users (
    id uuid primary key,
    full_name varchar(160) not null,
    nickname varchar(80) not null,
    email varchar(160) not null unique,
    phone varchar(40) not null,
    pix_key varchar(160) not null,
    password_hash varchar(255) not null,
    admin boolean not null default false,
    active boolean not null default true,
    created_at timestamp not null,
    updated_at timestamp not null,
    deleted_at timestamp
);

create table months (
    id uuid primary key,
    month integer not null check (month between 1 and 12),
    year integer not null,
    status varchar(20) not null,
    opened_at timestamp not null,
    closed_at timestamp,
    created_at timestamp not null,
    unique (month, year)
);

create table expenses (
    id uuid primary key,
    description varchar(255) not null,
    amount numeric(15, 2) not null check (amount > 0),
    expense_date date not null,
    category varchar(80) not null,
    payer_id uuid not null references app_users(id),
    month_id uuid not null references months(id),
    created_at timestamp not null,
    updated_at timestamp not null,
    deleted_at timestamp
);

create table expense_participants (
    id uuid primary key,
    expense_id uuid not null references expenses(id),
    user_id uuid not null references app_users(id),
    share_amount numeric(15, 2) not null check (share_amount >= 0),
    unique (expense_id, user_id)
);
