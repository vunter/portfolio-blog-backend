# Deployment Guide — Portfolio Blog

## Infrastructure Overview

| Component        | Details                                      |
|------------------|----------------------------------------------|
| **Provider**     | DigitalOcean Droplet                         |
| **IP**           | `146.190.67.249`                             |
| **Domain**       | `catananti.dev`                              |
| **OS**           | Ubuntu 24.04 LTS                             |
| **Resources**    | 2 vCPUs, 4 GB RAM                            |
| **User**         | `vunter`                                     |
| **Docker**       | 28.2.2                                       |
| **Doppler CLI**  | v3.75.3                                      |

---

## Architecture

```
Internet
  │
  ▼
Cloudflare (DNS + proxy)
  │
  ▼
┌──────────────────────────────────────────────────────────────────┐
│  Droplet (146.190.67.249)                                        │
│                                                                  │
│  ┌─────────┐   ┌──────────────┐   ┌────────────────────────┐    │
│  │  Nginx  │──▶│   Frontend   │   │  Datadog Agent         │    │
│  │  :80    │   │  Angular SSR │   │  APM + Logs + Metrics  │    │
│  │  :443   │   │  :4000       │   │  :8125/udp  :8126/tcp  │    │
│  └────┬────┘   └──────────────┘   └────────────────────────┘    │
│       │                                                          │
│       ▼                                                          │
│  ┌──────────────────────┐                                        │
│  │   Spring Boot API    │                                        │
│  │   Java 25 + WebFlux  │                                        │
│  │   :8080              │                                        │
│  │   + dd-java-agent    │                                        │
│  └───┬──────────┬───────┘                                        │
│      │          │                                                │
│      ▼          ▼                                                │
│  ┌────────┐  ┌───────┐  ┌────────────┐                           │
│  │Postgres│  │ Redis │  │ Prometheus │                           │
│  │  :5432 │  │ :6379 │  │   :9090    │                           │
│  └────────┘  └───────┘  └────────────┘                           │
└──────────────────────────────────────────────────────────────────┘
```

### Container Inventory (7 services)

| Container                  | Image                            | Memory Limit | Purpose                           |
|----------------------------|----------------------------------|--------------|-----------------------------------|
| `blog-postgres`            | `postgres:17-alpine`             | 512 MB       | Primary database (R2DBC)          |
| `portfolio-blog-api`       | `portfolio-blog-api:latest`      | 2 GB         | Spring Boot 4 API (Java 25, ZGC)  |
| `blog-redis`               | `redis:7-alpine`                 | 512 MB       | Session & cache (256 MB maxmem)   |
| `portfolio-blog-frontend`  | `portfolio-blog-frontend:latest` | 256 MB       | Angular SSR (Node + Nginx)        |
| `blog-nginx`               | `nginx:alpine`                   | 256 MB       | Reverse proxy, SSL termination    |
| `blog-prometheus`          | `prom/prometheus:v3.2.1`         | 512 MB       | Metrics collection (30d retention)|
| `datadog-agent`            | `gcr.io/datadoghq/agent:7`      | 512 MB       | APM, logs, infrastructure metrics |

---

## Secrets Management (Doppler)

All secrets are managed through [Doppler](https://dashboard.doppler.com). No `.env` files are used in deployment.

### How It Works

1. A **Doppler Service Token** is stored on the droplet at `~/.doppler_token` (mode `600`)
2. During deployment, the script sources this file: `source ~/.doppler_token`
3. `doppler run --` injects all secrets as environment variables into `docker compose up`
4. The same token is also stored as GitHub Actions secret `DOPPLER_TOKEN` (backup)

### Secrets Inventory

| Secret               | Used By         | Description                        |
|----------------------|-----------------|------------------------------------|
| `DB_PASSWORD`        | API, Postgres   | Application database password      |
| `POSTGRES_PASSWORD`  | Postgres        | Postgres superuser password         |
| `DB_USERNAME`        | API             | Database username (`blogadmin`)     |
| `REDIS_PASSWORD`     | API, Redis      | Redis authentication password       |
| `JWT_SECRET`         | API             | JWT signing key                     |
| `ADMIN_EMAIL`        | API             | Admin seed email                    |
| `ADMIN_PASSWORD`     | API             | Admin seed password (min 12 chars)  |
| `MAIL_PASSWORD`      | API             | Resend SMTP API key                 |
| `SENTRY_DSN`         | API             | Sentry error tracking DSN           |
| `DD_API_KEY`         | Datadog Agent   | Datadog API key                     |
| `DD_SITE`            | Datadog Agent   | Datadog site (`us5.datadoghq.com`)  |
| `CF_API_TOKEN`       | API             | Cloudflare API token                |
| `CF_ZONE_ID`         | API             | Cloudflare zone ID                  |
| `RECAPTCHA_SITE_KEY` | API             | reCAPTCHA v3 site key               |
| `RECAPTCHA_SECRET_KEY`| API            | reCAPTCHA v3 secret key             |
| `MFA_ENCRYPTION_KEY` | API             | AES key for TOTP secret encryption  |
| `NEXUS_HOST`         | CI (build only) | Nexus Maven repository host         |

### Rotating the Doppler Token

```bash
# On the droplet:
echo 'export DOPPLER_TOKEN=dp.st.prd.<new-token>' > ~/.doppler_token
chmod 600 ~/.doppler_token

# In GitHub Actions:
gh secret set DOPPLER_TOKEN --body "dp.st.prd.<new-token>" --repo vunter/portfolio-blog-backend
```

---

## CI/CD Pipeline

**Workflow:** `.github/workflows/deploy.yml`  
**Trigger:** Push to `main` branch  
**Runner:** `ubuntu-latest`

### Pipeline Stages

```
push to main
     │
     ▼
┌──────────┐     ┌────────────────────────────────────────────────────┐
│   test   │────▶│              build-and-deploy                      │
│          │     │                                                    │
│ mvnw     │     │ 1. Checkout backend + frontend repos              │
│ verify   │     │ 2. docker build API image                         │
│          │     │ 3. docker build Frontend image                    │
│ Java 25  │     │ 4. docker save + gzip → api.tar.gz, frontend.tar.gz│
│ Temurin  │     │ 5. SCP images to droplet                          │
│          │     │ 6. SCP config files (nginx, prometheus, etc.)     │
│ 1433     │     │ 7. SSH → docker load → doppler run -- compose up  │
│ tests    │     │                                                    │
└──────────┘     └────────────────────────────────────────────────────┘
```

### GitHub Actions Secrets Required

| Secret            | Description                      |
|-------------------|----------------------------------|
| `DROPLET_IP`      | Droplet IP address               |
| `SSH_PRIVATE_KEY`  | SSH key for `vunter@droplet`    |
| `NEXUS_HOST`      | Nexus Maven proxy (optional)     |
| `DOPPLER_TOKEN`   | Doppler service token (backup)   |

### Deploy Script (SSH step)

```bash
set -e
source ~/.doppler_token
cd ~/portfolio-blog
docker load < api.tar.gz
docker load < frontend.tar.gz
rm -f api.tar.gz frontend.tar.gz
cd deploy/cloud
doppler run -- docker compose -f docker-compose.cloud.yml up -d --force-recreate
docker image prune -f
```

---

## Observability

### Datadog (APM + Logs + Infrastructure)

| Feature                | Status    | Details                                      |
|------------------------|-----------|----------------------------------------------|
| **APM Tracing**        | Enabled   | `dd-java-agent.jar` attached to JVM          |
| **Log Collection**     | Enabled   | Autodiscovery labels on all containers        |
| **Infrastructure**     | Enabled   | CPU, memory, disk, network, container metrics |
| **DogStatsD**          | Enabled   | Custom metrics via UDP :8125                  |
| **Profiling**          | Disabled  | Saves RAM on 4 GB droplet                     |
| **Process Agent**      | Disabled  | Saves RAM on 4 GB droplet                     |
| **Dashboard**          | us5.datadoghq.com                              |

**DD Java Agent** is conditionally loaded based on `DD_AGENT_ENABLED`:
- `true` → JVM starts with `-javaagent:/opt/datadog/dd-java-agent.jar`
- `false` → JVM starts without the agent

**Autodiscovery Labels** are configured on:
- `blog-postgres` → source: `postgresql`
- `portfolio-blog-api` → source: `java`
- `blog-redis` → source: `redis` (with password auth for check)
- `blog-nginx` → source: `nginx`

### Prometheus

- Scrapes `/actuator/prometheus` from the API every 15s
- Retention: 30 days
- Accessible at `127.0.0.1:9090` (localhost only)
- Protected by HTTP basic auth via Nginx

### Micrometer

The API exports metrics to both Prometheus and Datadog:
- `micrometer-registry-prometheus` — pull-based (Prometheus scrapes)
- `micrometer-registry-datadog` — push-based (sends to DD API directly)

### Sentry

- DSN configured via `SENTRY_DSN` Doppler secret
- SDK: `sentry-spring-boot-starter-jakarta` 8.4.0
- **Note:** Auto-configuration is currently excluded (`spring.autoconfigure.exclude`) due to Spring Boot 4.x incompatibility; the DSN is set but Sentry is not fully functional

---

## Docker Build

### Multi-stage Dockerfile

| Stage       | Base Image                      | Purpose                              |
|-------------|---------------------------------|--------------------------------------|
| `builder`   | `eclipse-temurin:25-jdk-alpine` | Maven build, dependency resolution   |
| `runtime`   | `eclipse-temurin:25-jre-alpine` | Minimal runtime, non-root user       |

### Runtime Dependencies Installed

- **Chromium** + **Node.js** — Playwright PDF generation for resume export
- **curl** — health checks
- **Datadog Java agent** — APM tracing (`/opt/datadog/dd-java-agent.jar`)

### JVM Configuration

```
-XX:MaxRAMPercentage=75.0    # Use up to 75% of container memory (2 GB limit → ~1.5 GB heap)
-XX:+UseZGC                  # Low-latency garbage collector
```

### Security Hardening

- Non-root user (`appuser:appgroup`)
- Read-only filesystem (`read_only: true` in compose)
- tmpfs for `/tmp` (ephemeral writes)
- No shell access by default

---

## Database

| Setting         | Value                                                  |
|-----------------|--------------------------------------------------------|
| Engine          | PostgreSQL 17 (Alpine)                                 |
| Connection      | R2DBC (reactive, non-blocking)                         |
| Database        | `blog`                                                 |
| App User        | `blogadmin` (created by `init-db.sh`)                  |
| Superuser       | `postgres`                                             |
| Data Volume     | `postgres_data` (Docker named volume)                  |
| Exposed         | `127.0.0.1:5432` only (not publicly accessible)       |

### Admin User Initialization

The `AdminUserInitializer` component runs on startup:
- Creates the admin user from `ADMIN_EMAIL` / `ADMIN_PASSWORD` if it **does not exist**
- If the admin already exists, it does **not** update the password
- To change the admin password after initial creation, update the `password_hash` column directly in the database or use the password reset flow

---

## SSL / TLS

- **Certificate Provider:** Let's Encrypt
- **Managed by:** Certbot (standalone mode)
- **Auto-renewal:** Certbot timer (systemd)
- **Certificates location:** `/etc/letsencrypt/` (mounted read-only into Nginx)
- **Domains:** `catananti.dev`, `www.catananti.dev`

---

## Manual Operations

### SSH to Droplet

```bash
ssh vunter@146.190.67.249
```

### View Container Logs

```bash
docker logs portfolio-blog-api --tail 100 -f
docker logs datadog-agent --tail 50
docker logs blog-nginx --tail 50
```

### Check Container Health

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}'
```

### Restart a Single Service

```bash
cd ~/portfolio-blog/deploy/cloud
source ~/.doppler_token
doppler run -- docker compose -f docker-compose.cloud.yml restart app
```

### Update Admin Password

```bash
# Generate bcrypt hash (on droplet):
python3 -c "import bcrypt; print(bcrypt.hashpw(b'NEW_PASSWORD_HERE', bcrypt.gensalt(12)).decode())"

# Update in database:
docker exec blog-postgres psql -U postgres -d blog \
  -c "UPDATE users SET password_hash = '\$2b\$12\$HASH_HERE', updated_at = NOW() WHERE email = 'admin@catananti.dev';"
```

### Renew SSL Certificate

```bash
sudo certbot renew
# Or manually:
sudo certbot certonly --standalone -d catananti.dev -d www.catananti.dev
docker restart blog-nginx
```

### Check Doppler Secrets

```bash
source ~/.doppler_token
doppler run -- printenv | sort
doppler run -- printenv | grep DD_    # Datadog-specific
```

### Full Redeploy (manual)

```bash
cd ~/portfolio-blog/deploy/cloud
source ~/.doppler_token
doppler run -- docker compose -f docker-compose.cloud.yml down
doppler run -- docker compose -f docker-compose.cloud.yml up -d
```

---

## Ports Summary

| Port  | Service       | Binding          | Notes                        |
|-------|---------------|------------------|------------------------------|
| 80    | Nginx         | `0.0.0.0:80`    | HTTP → HTTPS redirect        |
| 443   | Nginx         | `0.0.0.0:443`   | HTTPS (Let's Encrypt)        |
| 8080  | API           | Internal only    | Proxied by Nginx              |
| 4000  | Frontend      | Internal only    | Proxied by Nginx              |
| 5432  | PostgreSQL    | `127.0.0.1:5432` | Localhost only               |
| 6379  | Redis         | `127.0.0.1:6379` | Localhost only               |
| 9090  | Prometheus    | `127.0.0.1:9090` | Localhost only               |
| 8125  | DD Agent      | Internal only    | DogStatsD (UDP)              |
| 8126  | DD Agent      | Internal only    | APM trace intake (TCP)       |

---

## Troubleshooting

| Symptom                           | Cause                                  | Fix                                                  |
|-----------------------------------|----------------------------------------|------------------------------------------------------|
| `Doppler Error: you must provide a token` | `~/.doppler_token` missing or empty | Re-create the file with the service token             |
| 401 on login with correct password | DB hash mismatches Doppler password   | Update `password_hash` in DB (see Manual Operations)  |
| Redis DD check `AuthenticationError` | Missing password in DD Autodiscovery  | Ensure `com.datadoghq.ad.instances` label has password |
| Container `unhealthy`             | Start period not elapsed / dependency  | Check `docker logs <container>`, wait for start_period |
| `dd-java-agent` not attached      | `DD_AGENT_ENABLED=false`              | Ensure compose sets it to `true` (Doppler or default)  |
| API slow on startup               | Cold DB connections + cache warming   | Normal; first requests take 2-3s, subsequent are fast  |
