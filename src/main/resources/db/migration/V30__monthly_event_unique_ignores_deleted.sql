-- Evento mensal apagado (soft delete) nao ocupa mais o mes: sem isso, apagar
-- o 12/2026 impedia criar o 12/2026 de novo ("Ja existe um evento mensal").
drop index if exists ux_events_monthly_group_month;

create unique index ux_events_monthly_group_month
    on events(group_id, month_id)
    where type = 'MONTHLY' and deleted_at is null;
