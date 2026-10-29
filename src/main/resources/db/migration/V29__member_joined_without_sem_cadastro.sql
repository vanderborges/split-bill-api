-- Aviso "Fulano entrou no grupo no lugar de "Y"": tira o "(sem cadastro)"
-- das notificacoes ja gravadas (o texto novo nao tem mais).
update notifications
set message = replace(message, '" (sem cadastro)', '"')
where type = 'MEMBER_JOINED'
  and message like '%" (sem cadastro)%';
