-- Tipo da notificacao: BILLING (cobranca, o que existia ate aqui) ou
-- MEMBER_JOINED (aviso pros admins de que alguem entrou no grupo).
alter table notifications add column type varchar(30) not null default 'BILLING';

-- Cobrancas antigas comecavam com "DividiAí:"; as novas comecam com o nome
-- do grupo do evento.
update notifications n
set message = g.name || substring(n.message from length('DividiAí') + 1)
from events e
join groups g on g.id = e.group_id
where n.event_id = e.id
  and n.message like 'DividiAí:%'
  and length(g.name || substring(n.message from length('DividiAí') + 1)) <= 500;
