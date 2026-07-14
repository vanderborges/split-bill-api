# Deploy

A URL da API usada pelo `deploy.ps1` e pelo `keep-render-awake.ps1` fica centralizada em
`deploy.settings.psd1`, na raiz do repositorio `repositorios`. Atualize apenas ali quando a URL mudar.

## Backend no Render

1. Crie um Postgres no Neon.
2. Copie host, database, user e password.
3. No Render, crie um Web Service usando este repositorio.
4. Use Docker.
5. Configure as variaveis:

```text
DB_URL=jdbc:postgresql://<host>/<database>?sslmode=require
DB_USERNAME=<user>
DB_PASSWORD=<password>
AUTH_TOKEN_SECRET=<segredo-grande>
CORS_ALLOWED_ORIGINS=https://split-bill-f135e.firebaseapp.com
KEEP_ALIVE_ENABLED=true
```

O Flyway cria as tabelas automaticamente na primeira subida.

`KEEP_ALIVE_ENABLED=true` liga um scheduler interno que faz ping em `/health` a cada 10 minutos
(usando a URL publica `RENDER_EXTERNAL_URL`, que o Render injeta automaticamente) para evitar que o
plano free hiberne por inatividade. Como este servico foi criado manualmente no Render (nao via
Blueprint), essa variavel precisa ser adicionada manualmente no dashboard tambem — o `render.yaml`
sozinho nao atualiza um servico ja existente.

URL da API publicada:

```text
https://split-bill-api-cc18.onrender.com
```

## Front Flutter Web

No deploy do front, use:

```bash
flutter build web --release --dart-define=API_BASE_URL=https://split-bill-api-cc18.onrender.com
```

Publique a pasta:

```text
build/web
```

Se usar Render Static Site, use o script `render-build.sh` do repositorio do front.

Depois do deploy do front, confirme no Render que `CORS_ALLOWED_ORIGINS` inclui a URL final do Firebase.
Para este projeto:

```text
CORS_ALLOWED_ORIGINS=https://split-bill-f135e.firebaseapp.com
```
