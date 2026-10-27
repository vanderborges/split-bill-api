-- Quem recebe os pagamentos passa a ser escolhido por EVENTO, no "Fechar
-- evento" (antes era um so por grupo, na tela Grupos). Sem recebedor =
-- sugestao de pagamentos (casa devedores com credores).
alter table events add column receiver_user_id uuid references app_users(id);

-- Eventos ja fechados ou aguardando pagamento mantem o recebedor que o
-- grupo tinha, pra sugestao/cobranca nao mudar no meio do acerto.
update events e
set receiver_user_id = g.receiver_user_id
from groups g
where e.group_id = g.id
  and e.status <> 'OPEN'
  and g.receiver_user_id is not null;

-- A opcao "Fechamento automatico" saiu do app: desliga o que estava
-- configurado (fechar agora pede a escolha de quem recebe).
update groups set auto_settlement_day = null where auto_settlement_day is not null;
