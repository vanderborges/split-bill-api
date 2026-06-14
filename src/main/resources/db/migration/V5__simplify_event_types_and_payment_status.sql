alter table expenses alter column month_id drop not null;

update event_settlements
set status = 'PAID'
where status in ('PAID_TO_ADMIN', 'RECEIVED_FROM_ADMIN', 'CONFIRMED');

update events
set type = 'SPORADIC'
where type = 'TRIP';
