-- "Pessoas sem cadastro" e "Pessoas temporarias" viraram um cadastro so
-- ("Convidar temporario"): a pessoa sem cadastro fixa no grupo passa a ser
-- temporaria do grupo (temporary = true, sem evento). Ela continua nas
-- listas enquanto tiver despesa em evento nao fechado (ou nenhuma despesa
-- ainda); o admin pode torna-la integrante (PUT .../members/{u}/promote).
update group_members gm
   set temporary = true,
       updated_at = now()
  from app_users u
 where u.id = gm.user_id
   and u.placeholder = true
   and u.deleted_at is null
   and gm.active = true
   and gm.temporary = false;
