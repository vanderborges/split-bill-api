alter table expense_participants
    add column share_count integer not null default 1,
    add column share_description varchar(160);

alter table expense_participants
    add constraint expense_participants_share_count_check check (share_count > 0);
