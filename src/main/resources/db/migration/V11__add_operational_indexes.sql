create index if not exists idx_events_group_deleted_status
    on events(group_id, deleted_at, status);

create index if not exists idx_events_group_month_type_deleted
    on events(group_id, month_id, type, deleted_at);

create index if not exists idx_expenses_event_deleted
    on expenses(event_id, deleted_at);

create index if not exists idx_expenses_month_deleted
    on expenses(month_id, deleted_at);

create index if not exists idx_expenses_date_deleted
    on expenses(expense_date, deleted_at);

create index if not exists idx_expenses_event_date_deleted
    on expenses(event_id, expense_date, deleted_at);

create index if not exists idx_expense_participants_user_expense
    on expense_participants(user_id, expense_id);

create index if not exists idx_expense_payers_user_expense
    on expense_payers(user_id, expense_id);

create index if not exists idx_group_members_user_active
    on group_members(user_id, active);

create index if not exists idx_group_members_group_active_role
    on group_members(group_id, active, role);

create index if not exists idx_event_settlements_event_user
    on event_settlements(event_id, user_id);
