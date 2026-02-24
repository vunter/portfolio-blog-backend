# CHANGELOG

## [Unreleased] - 2026-02-10 - Comprehensive Audit Implementation

All changes based on `COMPREHENSIVE_AUDIT_2026-02-10.md`. Excludes Spring Boot version (4.1.0-M1) and Java version (25) changes per user request.

---

### Critical Bug Fixes

#### BUG-CRÍTICO-01: Removed `.block()` in RateLimitingFilter (Deadlock Risk)
- **File**: `src/main/java/dev/catananti/config/RateLimitingFilter.java`
- Refactored `determineRateLimit()` from `int` (blocking) to `Mono<Integer>` (reactive)
- Removed `.block()` call that risked deadlocking Netty event loop threads
- Restructured filter chain to use `flatMap` on the reactive rate limit determination

#### BUG-CRÍTICO-02: Fixed Session Cookie Deletion (`maxAge(0)`)
- **File**: `src/main/java/dev/catananti/controller/AuthController.java`
- Changed `Duration.ofSeconds(0)` → `Duration.ofSeconds(-1)` in `addRefreshTokenCookie` for `rememberMe=false`
- `maxAge(-1)` creates a proper session cookie; `maxAge(0)` immediately deletes it

---

### Bug Fixes

#### BUG-03: Fixed SSR Cross-Request State Leak in Refresh Token Interceptor
- **File**: `frontend/src/app/core/interceptors/refresh-token.interceptor.ts`
- Moved module-scoped `isRefreshing` and `refreshSubject$` into injectable `RefreshTokenState` service
- Prevents shared state leaks between SSR requests

#### BUG-04: Migrated Contact Form to Full i18n
- **File**: `frontend/src/app/shared/components/contact-form/contact-form.component.ts`
- Replaced 15+ hardcoded `i18n.isEnglish() ? 'EN' : 'PT'` ternaries with `i18n.t('contactForm.xxx')` calls
- **File**: `frontend/src/app/core/services/i18n/en.ts` — Added 13 `contactForm.*` keys
- **File**: `frontend/src/app/core/services/i18n/pt.ts` — Added 13 `contactForm.*` keys
- **File**: `frontend/src/app/core/services/i18n/es.ts` — Added 13 `contactForm.*` keys (Spanish)
- **File**: `frontend/src/app/core/services/i18n/it.ts` — Added 13 `contactForm.*` keys (Italian)

#### BUG-06: Added `X-XSRF-TOKEN` to CORS Allowed Headers
- **File**: `src/main/java/dev/catananti/config/SecurityConfig.java`
- Added `"X-XSRF-TOKEN"` to `allowedHeaders` in CORS configuration

#### BUG-07: Fixed Duplicate `/blog/search` Route
- **File**: `frontend/src/app/features/blog/blog.routes.ts`
- Changed duplicate `/blog/search` route from `loadComponent` to `redirectTo: '/search'`

---

### Security Fixes

#### SEC-05: Replaced `bypassSecurityTrustHtml` with SafeIconPipe
- **New File**: `frontend/src/app/features/admin/pipes/safe-icon.pipe.ts`
  - Static `ICON_REGISTRY` map with all 10 admin menu SVG icons
  - `SafeIconPipe` resolves icon names to `SafeHtml`, logs warning for unknown icons
- **File**: `frontend/src/app/features/admin/layout/admin-layout.component.ts`
  - Removed `DomSanitizer` injection and `safeIcon()` method
  - Changed MenuItem `icon` type from `SafeHtml` to `string` (icon names)
  - All 10 menu items now use string keys
- **File**: `frontend/src/app/features/admin/layout/admin-layout.component.html`
  - Changed `[innerHTML]="item.icon"` → `[innerHTML]="item.icon | safeIcon"`

#### SEC-06: Hardened Grafana Configuration
- **File**: `docker-compose.yml`
  - Removed direct port `3000:3000` exposure
  - Changed password from `${GRAFANA_PASSWORD:-changeme}` to `${GRAFANA_PASSWORD:?GRAFANA_PASSWORD is required}`
  - Added `GF_SERVER_ROOT_URL` and `GF_SERVER_SERVE_FROM_SUB_PATH=true` for nginx proxy

---

### Incomplete Feature Fixes

#### FEAT-INC-05: Added Error Handling to Bookmark Sync
- **File**: `frontend/src/app/core/services/bookmark.service.ts`
  - Added error callback to `.subscribe()` for push-local-only-bookmarks operation

---

### Code Quality Improvements

#### CODE-01: Fixed Bare `.subscribe()` Calls (Missing Error Handling)
- **File**: `src/main/java/dev/catananti/service/CacheWarmingService.java`
  - `warmOnStartup()`: Added error logging callback to `.subscribe()`
  - `refreshPopularContent()`: Refactored nested `.subscribe()` to proper `flatMap` chain with `onErrorResume`
  - `prefetchRelatedContent()`: Added error logging callback to `.subscribe()`

#### CODE-02: Standardized Unsubscribe Pattern (`takeUntilDestroyed`)
- **File**: `frontend/src/app/features/resume/pages/template-editor/template-editor.component.ts`
  - Replaced `destroy$ = new Subject<void>()` + `takeUntil(this.destroy$)` with `DestroyRef` + `takeUntilDestroyed(this.destroyRef)`
  - Removed manual `destroy$.next()` / `destroy$.complete()` (kept `ngOnDestroy` for Monaco editor disposal)
- **File**: `frontend/src/app/features/blog/pages/search/search.component.ts`
  - Same migration to `DestroyRef` + `takeUntilDestroyed`
  - Removed `OnDestroy` interface and `ngOnDestroy()` method entirely

#### CODE-03/04: Cleaned Up Temp/Backup Files & Updated .gitignore
- Deleted 75+ `temp files and backups-cwd` files from project directory
- Deleted 3 `.bak_removed` files
- **File**: `.gitignore` — Added patterns: `temp files and backups`, `*.bak_removed`, `*.backup_*`

#### CODE-05: Moved `ContactFormData` Interface to Models
- **New File**: `frontend/src/app/models/contact-form.model.ts`
- **File**: `frontend/src/app/models/index.ts` — Added barrel export
- **File**: `frontend/src/app/shared/components/contact-form/contact-form.component.ts` — Updated import

---

### Infrastructure Improvements

#### INFRA-01: Optimized Frontend Dockerfile
- **File**: `frontend/Dockerfile`
- Added intermediate `runtime-deps` stage with `npm ci --omit=dev`
- Runtime image now only contains production dependencies instead of all node_modules

#### INFRA-02: Added Frontend Container Healthcheck
- **File**: `docker-compose.yml`
- Added healthcheck for `frontend` service: `wget --spider http://localhost:4000`

#### INFRA-03: Network Isolation
- **File**: `docker-compose.yml`
- Split `blog-network` into:
  - `blog-frontend-network` (bridge) — frontend, nginx, app
  - `blog-backend-network` (bridge, internal) — postgres, redis, prometheus, grafana, app, nginx
- `app` and `nginx` bridge both networks; database/cache isolated from direct frontend access

#### INFRA-04: Volume Mapping for Application Logs
- **File**: `docker-compose.yml`
- Added `app_logs:/app/logs` volume to the `app` service
- Added `app_logs` to volumes declaration

---

### UI Improvements

#### UI-08: Toast Notification for Code Copy
- **File**: `frontend/src/app/features/blog/pages/article-detail/article-detail.component.ts`
  - Added `this.notification.success(i18n.t('blog.codeCopied'))` on successful copy
  - Added `this.notification.error(i18n.t('blog.copyFailed'))` on clipboard API error
- Added i18n keys `blog.codeCopied` and `blog.copyFailed` to all 4 locale files (en, pt, es, it)

---

### Items Excluded (Per User Request)
- **SEC-01**: Spring Boot upgrade to 4.1.0 GA (Spring version change)
- **SEC-02**: Java 25 LTS configuration (Java version change)
