alter table events add column receiver_user_id uuid references app_users(id);
