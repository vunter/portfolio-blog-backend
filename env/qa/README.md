# QA Environment

Compose mínimo: app Spring Boot + Datadog Agent. Toda infraestrutura (Postgres `blog_qa`, Redis, frontend, nginx) está externa no K3s caseiro (DEV CLUSTER).

## Pré-requisitos no host de QA

- Docker Engine
- `doppler` CLI instalado e autenticado (`doppler login`)
- Acesso de rede ao K3s (`192.168.1.235:30432` para Postgres; endpoint Redis a confirmar)
- Imagem `portfolio-blog-api:qa` construída localmente (build acontece no `docker compose up`)
- (Opcional) GeoLite2 em `/home/<user>/geolite2/` se quiser ativar GeoIP — descomente o volume mount no compose

## Setup do Doppler

1. Crie um config `qa` no projeto `portfolio-blog` no Doppler
2. Popule com: `DATABASE_URL`, `DB_USERNAME`, `DB_PASSWORD`, `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `REDIS_PASSWORD`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS`, `APP_URL`, `APP_SITE_URL`, `S3_*`, `DD_API_KEY`, `MFA_ENCRYPTION_KEY`, etc.
3. Crie um service token e configure no host: `doppler configure set token <token> --scope <project_path>`

## Subir

```bash
cd env/qa
doppler run --config qa -- docker compose up -d --build
```

## Verificar saúde

```bash
docker compose ps
docker compose logs -f app
curl http://localhost:8080/actuator/health/readiness
```

## Banco QA

Postgres `blog_qa` no K3s. Schema é provisionado separadamente (não pelo compose). Para inicializar, execute o `schema.sql` manualmente uma vez:

```bash
# Via psql contra o NodePort do K3s
PGPASSWORD=<senha> psql -h 192.168.1.235 -p 30432 -U postgres -d blog_qa \
  -f ../../src/main/resources/schema.sql
```

## Parar

```bash
doppler run --config qa -- docker compose down
```
