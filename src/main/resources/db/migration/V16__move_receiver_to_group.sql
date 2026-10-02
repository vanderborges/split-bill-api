alter table events drop column receiver_user_id;
alter table groups add column receiver_user_id uuid references app_users(id);
