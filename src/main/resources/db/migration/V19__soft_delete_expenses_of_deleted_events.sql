-- Eventos apagados antes desta versao deixavam as despesas "vivas"
-- (deleted_at nulo), que continuavam aparecendo no Extrato. A partir de
-- agora EventUseCase.delete apaga as despesas junto; aqui corrige o legado.
update expenses
set deleted_at = events.deleted_at
from events
where expenses.event_id = events.id
  and events.deleted_at is not null
  and expenses.deleted_at is null;
