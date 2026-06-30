alter table expenses
    add column created_by_user_id uuid;

update expenses
set created_by_user_id = payer_id
where created_by_user_id is null;

alter table expenses
    alter column created_by_user_id set not null,
    add constraint expenses_created_by_user_fk foreign key (created_by_user_id) references app_users(id);
