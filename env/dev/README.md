# Dev Environment

Stack completa local para desenvolvimento: Postgres + Redis + MinIO + app + frontend + nginx + Prometheus + Grafana + Datadog Agent.

## Pré-requisitos

- Docker Engine / Docker Desktop em execução
- ~6 GB RAM livres para os containers
- `nginx/.htpasswd` criado localmente (gitignored). Gere com:
  ```bash
  htpasswd -c env/dev/nginx/.htpasswd admin
  ```

## Setup inicial

```bash
cp .env.example .env
# Edite .env e preencha JWT_SECRET, GRAFANA_PASSWORD, ADMIN_PASSWORD
```

## Subir o stack

```bash
# Da raiz do repositório portfolio-blog-backend
cd env/dev
docker compose up --build
```

Para incluir o MinIO (profile s3):
```bash
docker compose --profile s3 up --build
```

## Acessos

| Serviço | URL |
|---|---|
| App (via nginx) | http://localhost |
| Frontend (via nginx) | http://localhost |
| API (via nginx) | http://localhost/api |
| Grafana | http://localhost/grafana (admin / $GRAFANA_PASSWORD) |
| Prometheus | http://localhost/prometheus |
| MinIO console | http://localhost:9001 (minioadmin / minioadmin) |

## Vault opcional

Se quiser usar Vault em dev (em vez de `.env`), descomente as variáveis VAULT_* no `.env`. O app dá fallback automático para `.env` quando `VAULT_ROLE_ID` está vazio.

## Reset completo

```bash
docker compose down -v
```
