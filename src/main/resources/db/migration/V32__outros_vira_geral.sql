-- "Geral" e o tipo de despesa padrao: "Outros" (se existir) vira "Geral".
update expenses set category = 'Geral' where lower(category) = 'outros';
update expense_categories set active = false where lower(name) = 'outros';
insert into expense_categories (id, name, active, created_at)
select md5(random()::text || clock_timestamp()::text)::uuid, 'Geral', true, now()
where not exists (select 1 from expense_categories where lower(name) = 'geral');
update expense_categories set active = true where lower(name) = 'geral';
