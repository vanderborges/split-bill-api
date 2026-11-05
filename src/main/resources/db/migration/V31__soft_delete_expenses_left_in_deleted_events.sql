-- Antes da V30 o "Fechar evento" podia colocar a proxima parcela em um evento
-- mensal ja apagado: ela ficava viva, mas invisivel, e a serie parava de tombar
-- porque a parcela "ja existia". Mesma correcao de legado da V19. A partir de
-- agora InstallmentRolloverService ignora parcela de evento apagado.
update expenses
set deleted_at = events.deleted_at
from events
where expenses.event_id = events.id
  and events.deleted_at is not null
  and expenses.deleted_at is null;
