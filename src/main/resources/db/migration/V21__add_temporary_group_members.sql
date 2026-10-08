-- Pessoa temporaria: conta existente que entra no grupo so para UM evento
-- (via convite temporario). Ela so enxerga/participa desse evento e some
-- das listas quando ele fecha, mas continua no grupo (oculta) para poder
-- ser reativada em outro evento.
alter table group_members add column temporary boolean not null default false;
alter table group_members add column temporary_event_id uuid references events(id);

-- Convite temporario: amarrado a um evento. Convites normais (do grupo)
-- continuam com event_id nulo.
alter table group_invites add column event_id uuid references events(id);
