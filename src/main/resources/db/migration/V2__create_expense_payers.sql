create table expense_payers (
    id uuid primary key,
    expense_id uuid not null references expenses(id),
    user_id uuid not null references app_users(id),
    paid_amount numeric(15, 2) not null check (paid_amount > 0),
    unique (expense_id, user_id)
);

insert into expense_payers (id, expense_id, user_id, paid_amount)
select md5(random()::text || clock_timestamp()::text)::uuid, id, payer_id, amount
from expenses
where deleted_at is null;
