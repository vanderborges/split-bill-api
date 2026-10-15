-- Ajuste de dados pedido pelo usuario em 15/10/2026: marcar como pagos os
-- acertos pendentes do grupo "Magrices" nos eventos que NAO estao abertos
-- (aguardando pagamento ou fechados) e concluir os que estavam aguardando
-- pagamento - o mesmo efeito do admin marcando um a um no Relatorio (que
-- fecha o evento sozinho quando o ultimo pagamento e confirmado, ver
-- EventSettlementController#closeIfFullySettled e EventUseCase#close).
--
-- So age se existir exatamente UM grupo ativo com esse nome; senao nao
-- altera nada (uma migracao com erro derrubaria a subida do backend).
do $$
declare
    magrices_id uuid;
begin
    if (select count(*) from groups where name = 'Magrices' and active) <> 1 then
        raise notice 'V23: grupo Magrices nao encontrado (ou duplicado) - nada alterado';
        return;
    end if;
    select id into magrices_id from groups where name = 'Magrices' and active;

    update event_settlements s
       set status = 'PAID',
           updated_at = now()
      from events e
     where s.event_id = e.id
       and e.group_id = magrices_id
       and e.deleted_at is null
       and e.status <> 'OPEN'
       and s.status = 'PENDING';

    -- Mesmo que EventUseCase#close faz com o mes de um evento mensal.
    update months m
       set status = 'CLOSED',
           closed_at = now()
      from events e
     where e.month_id = m.id
       and e.group_id = magrices_id
       and e.deleted_at is null
       and e.type = 'MONTHLY'
       and e.status = 'SETTLING'
       and m.status <> 'CLOSED';

    update events
       set status = 'CLOSED',
           closed_at = now()
     where group_id = magrices_id
       and deleted_at is null
       and status = 'SETTLING';
end $$;
