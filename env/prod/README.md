# Production Environment (DigitalOcean Droplet)

Deploy target: Droplet `prod` 146.190.67.249 (`catananti.dev`), hostname `portfolio-blog`. Cloudflare na frente como CDN/proxy/WAF; nginx local termina TLS com Let's Encrypt.

## Estrutura

Dois composes com lifecycles independentes:

- **`docker-compose.infra.yml`** (project `blog-infra`) — Postgres + Redis. Long-lived; raramente reiniciado.
- **`docker-compose.cloud.yml`** (project `blog-cloud`) — app + frontend + nginx + datadog-agent. Reiniciado a cada deploy.

Rede `blog-infra` é compartilhada entre os dois (declarada como `external: true` em `cloud`).

## Pré-requisitos no Droplet

- Docker Engine + Docker Compose v2
- `doppler` CLI instalado, autenticado, e `DOPPLER_TOKEN` salvo em `~/.doppler/`
- Imagens `portfolio-blog-api` e `portfolio-blog-frontend` construídas pelo CI e disponíveis localmente (`docker images | grep portfolio-blog`)
- GeoLite2 database em `/home/vunter/geolite2/GeoLite2-Country.mmdb` (gerenciado por `scripts/update-geolite2.sh`)
- Let's Encrypt certs em `/etc/letsencrypt/live/catananti.dev/` (renovação via certbot)
- `env/prod/.htpasswd` criado localmente para o endpoint `/actuator/` protegido (gitignored — gerar com `htpasswd -c env/prod/.htpasswd admin`)

## Primeiro deploy

```bash
cd env/prod

# 1. Sobe infra (uma vez ou raramente)
doppler run --config prd -- docker compose \
  -p blog-infra -f docker-compose.infra.yml up -d

# 2. Sobe app stack
doppler run --config prd -- docker compose \
  -p blog-cloud -f docker-compose.cloud.yml up -d
```

## Redeploy do app

```bash
cd env/prod
doppler run --config prd -- docker compose \
  -p blog-cloud -f docker-compose.cloud.yml pull
doppler run --config prd -- docker compose \
  -p blog-cloud -f docker-compose.cloud.yml up -d
```

## Rollback

`rollback.sh` reverte para uma imagem anterior por SHA (do GHCR + opcionalmente config bundle do Nexus).

```bash
cd env/prod
./rollback.sh <commit-sha>
```

## Backup do banco

`scripts/backup-db.sh` faz pg_dump → Cloudflare R2 (`catananti-backups`, bucket privado). Rotaciona 30 dias diário + semanal aos domingos. Crontab:

```
0 3 * * * /home/vunter/portfolio-blog-backend/env/prod/scripts/backup-db.sh
```

## Atualizar GeoLite2

`scripts/update-geolite2.sh` baixa o GeoLite2-Country.mmdb mais recente do MaxMind (requer `MAXMIND_LICENSE_KEY` via Doppler). Crontab semanal:

```
0 3 * * 3 /home/vunter/portfolio-blog-backend/env/prod/scripts/update-geolite2.sh
```

## Observability

Datadog APM via sidecar `datadog-agent`. Logs/métricas vão para Datadog us5. **Prometheus não roda mais localmente** — o `datadog-agent` faz scrape de `/actuator/prometheus` do app diretamente.

## Migração vinda do setup antigo

Esta estrutura substitui `/home/vunter/portfolio-blog/deploy/cloud/` no Droplet. Promover via:

1. Push da branch `feat/env-folders` → CI builda imagens.
2. No Droplet: `git pull` (ou rsync) do novo conteúdo.
3. Comparar: `diff docker-compose.cloud.yml /home/vunter/portfolio-blog/deploy/cloud/docker-compose.cloud.yml` — diferenças esperadas: sem `prometheus`, mount de nginx consolidado (`./nginx.conf` em vez de `../../nginx/nginx.conf` + `./nginx-cloud.conf`), `./.htpasswd` local em vez de `../../nginx/.htpasswd`.
4. Deploy controlado: `doppler run --config prd -- docker compose -p blog-cloud -f docker-compose.cloud.yml up -d` (mantém `rollback.sh` à mão).
