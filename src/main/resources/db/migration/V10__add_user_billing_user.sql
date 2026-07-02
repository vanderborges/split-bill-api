alter table app_users
    add column billing_user_id uuid references app_users(id);
