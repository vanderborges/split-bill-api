alter table installment_groups add column is_subscription boolean not null default false;
alter table installment_groups alter column total_installments drop not null;
