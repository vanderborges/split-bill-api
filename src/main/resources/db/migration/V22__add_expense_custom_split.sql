-- Divisao por valor: cada participante tem um valor informado manualmente
-- (em vez de dividir igualmente por cotas). O share_amount de cada
-- participante ja guarda o valor; esta flag so diz como a despesa foi
-- dividida, pra edicao abrir no modo certo e o rollover de parcela/assinatura
-- copiar os valores em vez de recalcular por cotas.
alter table expenses add column custom_split boolean not null default false;
