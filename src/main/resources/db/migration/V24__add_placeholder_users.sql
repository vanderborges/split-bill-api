-- Pessoa sem cadastro (estilo Tricount): o admin do grupo cadastra so o
-- nome; ela vira um usuario "fantasma" que entra nas despesas como qualquer
-- integrante, mas nao faz login. Quando alguem entra pelo convite e escolhe
-- esse nome, tudo do fantasma passa pra conta real e o fantasma e apagado
-- (ver PlaceholderUseCase). Email/senha do fantasma sao valores internos
-- impossiveis de usar no login.
alter table app_users
    add column placeholder boolean not null default false;
