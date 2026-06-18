# Deploy

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
CORS_ALLOWED_ORIGINS=https://<url-do-front>
```

O Flyway cria as tabelas automaticamente na primeira subida.

## Front Flutter Web

No deploy do front, use:

```bash
flutter build web --release --dart-define=API_BASE_URL=https://<url-da-api>
```

Publique a pasta:

```text
build/web
```

Se usar Render Static Site, use o script `render-build.sh` do repositorio do front.
