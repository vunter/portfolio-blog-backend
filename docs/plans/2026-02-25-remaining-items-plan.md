# Remaining Items — Implementation Plan (Updated for Submodule Structure)
**Created:** 2026-02-25
**Updated for:** GitHub submodule architecture

---

## Repository Structure

```
github.com/vunter/portfolio-blog          (parent — submodules only)
├── backend/  → github.com/vunter/portfolio-blog-backend
├── frontend/ → github.com/vunter/portfolio-blog-frontend
├── docker-compose.dev.yml
└── README.md

Standalone clones (for CI/CD):
  C:\...\portfolio-blog-backend\   ← Spring Boot 4, Java 25, WebFlux
  C:\...\portfolio-blog-frontend\  ← Angular 20, SSR, PWA
```

**Key implications:**
- CI/CD runs on **each submodule repo independently** (each has its own `.github/workflows/`)
- Codecov, DeepSource, Sentry → configured per-repo, not on parent
- Deploy workflow in `portfolio-blog-backend` already checks out frontend via `actions/checkout` with `repository: vunter/portfolio-blog-frontend`

---

## Status Overview

| # | Item | Est. | Status | Needs User Input? |
|---|------|------|--------|-------------------|
| 1 | **2FA/MFA (TOTP + Email OTP)** | 8–12h | 🔴 NOT STARTED | No |
| 2 | **Sentry Error Tracking** | 1h | 🟡 Keys in Vault + Doppler | No |
| 3 | **LoginAttemptService Caffeine Fallback** | 1h | 🔴 NOT STARTED | No |
| 4 | **F-214 ResumeProfileService Upsert** | 3–4h | 🔴 NOT STARTED | No |
| 5 | **Codecov CI Upload** | 30min | ⏸️ BLOCKED | Yes — GitHub setup |
| 6 | **DeepSource** | 15min | ⏸️ BLOCKED | Yes — GitHub setup |
| 7 | **DigitalOcean Deployment (Student Pack)** | 2h | 📋 PLAN BELOW | Partial |
| 8 | ~~R2 CDN custom domain~~ | — | ✅ DONE (`cdn.catananti.dev`) | — |
| 9 | ~~DigitalOcean $200 credit~~ | — | ✅ DONE | — |
| 10 | ~~F-252 Plaintext tokens~~ | — | ✅ DONE (SHA-256) | — |

---

## 5. Codecov — Setup Guide (per-repo)

### What you do:

1. **Go to** [codecov.io](https://app.codecov.io) → Sign in with GitHub
2. **Add repo:** `vunter/portfolio-blog-backend`
3. **Copy the `CODECOV_TOKEN`** from the repo settings page
4. **Add as GitHub Actions secret:**
   - Go to `github.com/vunter/portfolio-blog-backend` → Settings → Secrets and variables → Actions
   - New repository secret: Name = `CODECOV_TOKEN`, Value = *(paste token)*
5. (Optional) Also add `vunter/portfolio-blog-frontend` if you want frontend coverage later

### What I'll do (after you provide the token):

Add step to `portfolio-blog-backend/.github/workflows/ci.yml`:
```yaml
      - name: Upload coverage to Codecov
        uses: codecov/codecov-action@v5
        with:
          token: ${{ secrets.CODECOV_TOKEN }}
          files: target/site/jacoco/jacoco.xml
          flags: backend
          fail_ci_if_error: false
```

JaCoCo is already configured in `pom.xml` and generates `target/site/jacoco/jacoco.xml`.

---

## 6. DeepSource — Setup Guide (per-repo)

### What you do:

1. **Go to** [deepsource.io](https://app.deepsource.com) → Sign in with GitHub
2. **Activate repo:** `vunter/portfolio-blog-backend`
3. **Copy the `DEEPSOURCE_DSN`** from Settings → Reporting
4. **Add as GitHub Actions secret:**
   - Go to `github.com/vunter/portfolio-blog-backend` → Settings → Secrets and variables → Actions
   - New repository secret: Name = `DEEPSOURCE_DSN`, Value = *(paste DSN)*
5. (Optional) Also activate `vunter/portfolio-blog-frontend` for JS/TS analysis

### What I'll do (after you activate):

**Backend — Create `portfolio-blog-backend/.deepsource.toml`:**
```toml
version = 1

[[analyzers]]
name = "java"
enabled = true

  [analyzers.meta]
  runtime_version = "25"
```

**Frontend — Create `portfolio-blog-frontend/.deepsource.toml`:**
```toml
version = 1

[[analyzers]]
name = "javascript"
enabled = true

  [analyzers.meta]
  environment = ["browser", "node"]
  dialect = "typescript"

[[transformers]]
name = "prettier"
enabled = true
```

**Backend CI — Add coverage report step to `ci.yml`:**
```yaml
      - name: Report coverage to DeepSource
        uses: deepsourcelabs/test-coverage-action@master
        with:
          key: java
          coverage-file: target/site/jacoco/jacoco.xml
          dsn: ${{ secrets.DEEPSOURCE_DSN }}
```

---

## 2. Sentry Error Tracking (~1h)

### Status: Keys already in Vault (`sentry.dsn`, `sentry.auth-token`) + Doppler (`SENTRY_DSN`, `SENTRY_AUTH_TOKEN`, `SENTRY_ORG`, `SENTRY_PROJECT`) ✅

### Configuration per repo:

**Sentry is backend-only** — Spring Boot catches unhandled exceptions. Angular errors can be added later via `@sentry/angular`.

### Remaining work (backend repo):

1. **Add dependency** to `portfolio-blog-backend/pom.xml`:
   ```xml
   <dependency>
       <groupId>io.sentry</groupId>
       <artifactId>sentry-spring-boot-starter-jakarta</artifactId>
       <version>8.4.0</version>
   </dependency>
   ```

2. **Add config** to `application.properties`:
   ```properties
   # ── Sentry Error Tracking ──────────────────────
   sentry.dsn=${SENTRY_DSN:}
   sentry.traces-sample-rate=0.2
   sentry.environment=${SPRING_PROFILES_ACTIVE:dev}
   sentry.send-default-pii=false
   sentry.release=${APP_VERSION:0.0.1-SNAPSHOT}
   ```
   - **Local cluster:** Vault auto-resolves `sentry.dsn` from `secret/portfolio-blog`
   - **Cloud (Doppler):** Already has `SENTRY_DSN` → auto-mapped via `${SENTRY_DSN:}`

3. **Add env var** to `deploy/cloud/docker-compose.cloud.yml` (app service):
   ```yaml
   - SENTRY_DSN=${SENTRY_DSN:-}
   ```

4. **No code changes** — Spring Boot auto-config captures all unhandled exceptions

### Optional: Frontend Sentry (later)
```bash
cd portfolio-blog-frontend
npm install @sentry/angular
```
Then instrument `app.config.ts` with `Sentry.init()`. Can defer to next sprint.

---

## 7. DigitalOcean Deployment — GitHub Student Pack Strategy

### What you get (GitHub Student Pack):

| Benefit | Value | Duration |
|---------|-------|----------|
| **DO $200 credit** | $200 | 12 months |
| **Namecheap .me domain** | Free 1yr | 12 months |
| **GitHub Pro** | Free | While student |
| **Sentry** | 500K events/mo | While student |
| **DeepSource** | Free Pro tier | While student |

### Current Deployment (already working):

```
GitHub Actions (deploy.yml on portfolio-blog-backend)
  → Build Docker images (API + Frontend)
  → SCP to Droplet (146.190.67.249)
  → docker load → doppler run -- docker compose up -d
```

### Recommended Droplet Sizing with $200 Credit

**Current setup:** Likely a Basic Droplet ($6–12/mo)

**Optimal for $200 credit over 12 months ≈ $16/mo budget:**

| Option | Spec | $/mo | Lasts | Fit |
|--------|------|------|-------|-----|
| **Basic $12** | 2 vCPU, 2GB, 60GB SSD | $12 | 16 months | ✅ Tight but works |
| **Basic $18** | 2 vCPU, 4GB, 80GB SSD | $18 | 11 months | ✅✅ Comfortable |
| **Basic $24** | 4 vCPU, 8GB, 160GB SSD | $24 | 8 months | ⚠️ Overkill |

**Recommendation:** **$12/mo Basic Droplet** (2 vCPU, 2GB RAM) — the Java app has a 2GB memory limit in docker-compose, and with `read_only: true` + Alpine images, this is already lean. Total stack memory:
- App (Spring Boot): 2GB limit
- PostgreSQL: 512MB limit
- Redis: 512MB limit (256MB maxmemory)
- Frontend (Nginx): 256MB limit
- Nginx reverse proxy: 256MB limit
- Prometheus: 512MB limit
- **Total: ~4GB** → needs at least the **$18/mo (4GB) plan**

**My recommendation:** **$18/mo (2 vCPU, 4GB RAM, 80GB SSD)**
- Fits all services comfortably
- $200 credit lasts ~11 months
- After credit expires: $18/mo out of pocket (or downscale)

### Deployment Improvements to Consider

#### A. Use DigitalOcean Container Registry (DOCR) — FREE tier

Instead of SCP-ing tar.gz images, push to DO's container registry:

```yaml
# In deploy.yml — replace the SCP + docker load steps:
      - name: Login to DOCR
        uses: digitalocean/action-doctl@v2
        with:
          token: ${{ secrets.DO_API_TOKEN }}
      - run: doctl registry login

      - name: Build & Push API
        run: |
          docker build -t registry.digitalocean.com/vunter/portfolio-blog-api:${{ github.sha }} .
          docker push registry.digitalocean.com/vunter/portfolio-blog-api:${{ github.sha }}

      - name: Build & Push Frontend
        run: |
          docker build -t registry.digitalocean.com/vunter/portfolio-blog-frontend:${{ github.sha }} frontend/
          docker push registry.digitalocean.com/vunter/portfolio-blog-frontend:${{ github.sha }}

      - name: Deploy on Droplet
        uses: appleboy/ssh-action@v1.0.3
        with:
          host: ${{ secrets.DROPLET_IP }}
          username: vunter
          key: ${{ secrets.SSH_PRIVATE_KEY }}
          script: |
            doctl registry login
            cd ~/portfolio-blog/deploy/cloud
            doppler run -- docker compose -f docker-compose.cloud.yml pull
            doppler run -- docker compose -f docker-compose.cloud.yml up -d
            docker image prune -f
```

**Pros:** No large file SCP, faster deploys, image version history, rollback by tag
**Cons:** DOCR free tier = 500MB storage (should be fine for 2 images)

**Required secret:** `DO_API_TOKEN` — generate at https://cloud.digitalocean.com/account/api/tokens

#### B. Current approach (SCP) — already working ✅

Keep as-is. It works, no additional setup needed. The deploy.yml in `portfolio-blog-backend` already handles everything.

### Action items for you:

1. **Check your Droplet plan** — if it's the $6 (1GB) plan, **upgrade to $18 (4GB)** to avoid OOM
2. **Optional:** Create a DOCR registry at https://cloud.digitalocean.com/registry → copy `DO_API_TOKEN` → add as GitHub secret
3. **Doppler** is already configured — no changes needed

---

## 1. 2FA / MFA — TOTP + Email OTP (ASAP)

### 1.1 Scope
- Available for **all users** (VIEWER, EDITOR, DEV, ADMIN)
- Two MFA methods: **TOTP app** (Google Authenticator, Bitwarden, Authy, etc.) and **Email OTP**
- TOTP setup shows both **QR code** and **plain-text secret key**
- **Recovery codes** generated on setup (one-time use, 8 codes)
- No SMS

### 1.2 Which repo gets what

| Repo | Changes |
|------|---------|
| `portfolio-blog-backend` | Schema, entity, repository, service, controller, DTOs, tests |
| `portfolio-blog-frontend` | MFA verify page, security settings page, auth service methods |

### 1.3 Database Changes

**New table: `user_mfa_config`**
```sql
CREATE TABLE IF NOT EXISTS user_mfa_config (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    mfa_type        VARCHAR(20) NOT NULL,           -- 'TOTP' or 'EMAIL'
    totp_secret     VARCHAR(255),                    -- encrypted TOTP secret (AES-256)
    totp_verified   BOOLEAN DEFAULT FALSE,           -- setup completed?
    recovery_codes  TEXT,                             -- JSON array of hashed codes
    enabled         BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, mfa_type)
);
CREATE INDEX IF NOT EXISTS idx_user_mfa_user ON user_mfa_config(user_id);
```

**Alter `users` table:**
```sql
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_enabled BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS mfa_preferred_method VARCHAR(20);
```

### 1.4 Backend — New Files (in `portfolio-blog-backend`)

| File | Purpose |
|------|---------|
| `entity/UserMfaConfig.java` | R2DBC entity for `user_mfa_config` |
| `repository/UserMfaConfigRepository.java` | Reactive CRUD + `findByUserIdAndMfaType`, `findAllByUserId` |
| `service/MfaService.java` | TOTP secret generation, QR URI, OTP verification, recovery codes |
| `service/EmailOtpService.java` | Generate 6-digit code, store in Redis (5min TTL), verify |
| `controller/MfaController.java` | REST endpoints |
| `dto/mfa/*` | Request/Response DTOs |
| `config/MfaProperties.java` | Config: issuer name, OTP length, recovery code count |
| `util/AesEncryptor.java` | AES-256-GCM for encrypting TOTP secrets at rest |

### 1.5 Dependencies (`portfolio-blog-backend/pom.xml`)
```xml
<!-- TOTP (RFC 6238) -->
<dependency>
    <groupId>com.eatthepath</groupId>
    <artifactId>java-otp</artifactId>
    <version>0.4.0</version>
</dependency>
<!-- QR code generation -->
<dependency>
    <groupId>com.google.zxing</groupId>
    <artifactId>javase</artifactId>
    <version>3.5.3</version>
</dependency>
<dependency>
    <groupId>com.google.zxing</groupId>
    <artifactId>core</artifactId>
    <version>3.5.3</version>
</dependency>
```

### 1.6 API Endpoints

**Setup & Management** (authenticated)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/admin/auth/mfa/totp/setup` | Generate TOTP secret → `{secret, qrCodeBase64, otpAuthUri}` |
| `POST` | `/api/v1/admin/auth/mfa/totp/verify-setup` | Verify first TOTP code → returns recovery codes |
| `POST` | `/api/v1/admin/auth/mfa/email/enable` | Enable email OTP |
| `DELETE` | `/api/v1/admin/auth/mfa/totp` | Disable TOTP (requires password) |
| `DELETE` | `/api/v1/admin/auth/mfa/email` | Disable email OTP (requires password) |
| `GET` | `/api/v1/admin/auth/mfa/status` | Get MFA status |
| `PUT` | `/api/v1/admin/auth/mfa/preferred` | Set preferred method |
| `POST` | `/api/v1/admin/auth/mfa/recovery/regenerate` | Regenerate recovery codes (requires password) |

**Login Flow** (public/semi-public)

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/admin/auth/login/v2` | **Modified:** returns `{mfaRequired, mfaToken, methods}` if MFA enabled |
| `POST` | `/api/v1/admin/auth/mfa/challenge` | Send email OTP |
| `POST` | `/api/v1/admin/auth/mfa/verify` | Verify TOTP/Email code → JWT + cookies |
| `POST` | `/api/v1/admin/auth/mfa/recovery` | Verify recovery code → JWT + cookies |

### 1.7 Login Flow — Step by Step

```
1. POST /login/v2 { email, password, recaptchaToken }
2. AuthService validates credentials (same as today)
3. IF user.mfaEnabled == false → JWT + cookies (no change)
4. IF user.mfaEnabled == true:
   → Generate mfaToken (short-lived JWT, 5min TTL)
   → Return 200 { mfaRequired: true, mfaToken, methods: ["TOTP","EMAIL"] }
   → NO auth cookies set
5. Frontend routes to /mfa-verify
6. User picks method:
   - TOTP → enters 6-digit code from app
   - Email → clicks "Send code" → POST /mfa/challenge → enters code
7. POST /mfa/verify { mfaToken, code, method }
   → Validate mfaToken → verify code → JWT + cookies
8. Recovery: POST /mfa/recovery { mfaToken, recoveryCode }
   → Hash & match → mark used → JWT + cookies
```

### 1.8 TOTP Setup Flow

```
1. POST /mfa/totp/setup (authenticated)
   → SecureRandom 160-bit secret → AES-256-GCM encrypt → store
   → Build otpauth://totp/Catananti:{email}?secret={base32}&issuer=Catananti&digits=6&period=30
   → ZXing QR code PNG → Base64
   → Return { secret, qrCodeBase64, otpAuthUri }

2. Frontend shows QR code image + plain text key + code input

3. POST /mfa/totp/verify-setup { code }
   → Decrypt secret → validate TOTP (±1 window)
   → Set enabled=true, user.mfaEnabled=true
   → Generate 8 recovery codes (SHA-256 hashed in DB)
   → Return { recoveryCodes: ["ABCD-1234", ...] } — shown ONCE
```

### 1.9 Frontend Changes (in `portfolio-blog-frontend`)

| File | Change |
|------|--------|
| `features/auth/pages/login/login.component.ts` | Check `mfaRequired` → route to MFA verify |
| **NEW** `features/auth/pages/mfa-verify/` | MFA code input, method selector, recovery link |
| `core/auth/auth.service.ts` | Add `mfaVerify()`, `mfaChallenge()`, `setupTotp()`, etc. |
| **NEW** `features/settings/pages/security/` | Enable/disable TOTP/email, QR display, recovery codes |
| `app.routes.ts` | Add `/mfa-verify`, `/settings/security` |

### 1.10 Security
- TOTP secret encrypted at rest (AES-256-GCM, key from `MFA_ENCRYPTION_KEY`)
- Recovery codes hashed (SHA-256, single-use)
- mfaToken: 5min TTL JWT, only for MFA verification
- Rate limit: 5 attempts per mfaToken
- Email OTP: Redis, 5min TTL, single-use
- Audit log entries for all MFA events

### 1.11 New Secrets

| Key | Vault | Doppler | Notes |
|-----|-------|---------|-------|
| `mfa.encryption-key` | `secret/portfolio-blog` | `MFA_ENCRYPTION_KEY` | 32-byte AES key (auto-generate) |

---

## 3. LoginAttemptService Caffeine Fallback (~1h)

**Repo:** `portfolio-blog-backend`

### Changes:
1. Add `Caffeine<String, AttemptInfo>` cache (10K entries, 30min TTL)
2. On Redis error → fall back to Caffeine (not fail-open)
3. All methods: try Redis first → `.onErrorResume()` → Caffeine

---

## 4. F-214 ResumeProfileService Upsert (~3–4h)

**Repo:** `portfolio-blog-backend`

### Changes:
1. Add `id` to incoming DTOs (null = insert, present = update)
2. For each child entity: `deleteByProfileIdAndIdNotIn()` + `saveAll()`
3. Covers all 11 child tables

---

## Execution Order

| Priority | Item | Repo | Est. |
|----------|------|------|------|
| 🔴 1 | **2FA/MFA** | backend + frontend | 8–12h |
| 🟠 2 | **Sentry** | backend | 1h |
| 🟡 3 | **LoginAttempt Caffeine** | backend | 1h |
| 🟢 4 | **F-214 Upsert** | backend | 3–4h |
| ⏸️ 5 | **Codecov** | backend CI | 30min |
| ⏸️ 6 | **DeepSource** | both repos | 15min |

---

## User Action Items Summary

| Item | What to do | Where |
|------|-----------|-------|
| **Codecov** | Sign in → add `vunter/portfolio-blog-backend` → copy token | [codecov.io](https://app.codecov.io) |
| | Add `CODECOV_TOKEN` as repo secret | GitHub → backend repo → Settings → Secrets |
| **DeepSource** | Sign in → activate `vunter/portfolio-blog-backend` → copy DSN | [deepsource.io](https://app.deepsource.com) |
| | Add `DEEPSOURCE_DSN` as repo secret | GitHub → backend repo → Settings → Secrets |
| | (Optional) Also activate `vunter/portfolio-blog-frontend` | DeepSource dashboard |
| **DigitalOcean** | Check Droplet plan — upgrade to 4GB ($18/mo) if on 1-2GB | DO dashboard |
| | (Optional) Create container registry for faster deploys | DO dashboard → Container Registry |
| | (Optional) Add `DO_API_TOKEN` as repo secret | GitHub → backend repo → Settings → Secrets |
