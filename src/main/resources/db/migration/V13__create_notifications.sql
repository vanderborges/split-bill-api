create table notifications (
    id uuid primary key,
    recipient_user_id uuid not null references app_users(id),
    event_id uuid references events(id),
    message varchar(500) not null,
    created_by_user_id uuid not null references app_users(id),
    created_at timestamp not null,
    read_at timestamp
);

create index idx_notifications_recipient on notifications(recipient_user_id, created_at desc);
create index idx_notifications_recipient_unread on notifications(recipient_user_id) where read_at is null;
