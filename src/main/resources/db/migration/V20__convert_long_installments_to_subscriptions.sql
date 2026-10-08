-- Antes de existir assinatura (V15), assinaturas eram cadastradas como
-- parcelamento com um numero enorme de parcelas (> 100). Converte esses
-- registros para assinatura de verdade: sem total de parcelas, repete todo
-- mes ate ser cancelada.
update expenses
set total_installments = null
where installment_group_id in (
    select id from installment_groups where total_installments > 100
);

update installment_groups
set is_subscription = true,
    total_installments = null
where total_installments > 100;
