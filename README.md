# Portfolio Blog V2

Fullstack reactive portfolio and blog platform with resume builder, built with Java 25 + Spring Boot 4.1 + Angular 20.

## Tech Stack

### Backend
- **Java 25** with virtual threads, pattern matching, sealed classes
- **Spring Boot 4.1.0-M1** + **Spring WebFlux** (reactive)
- **Spring Data R2DBC** with PostgreSQL (r2dbc-postgresql)
- **Spring Security** (reactive) with JWT authentication
- **Redis** (reactive) for caching and rate limiting
- **Caffeine** for local caching
- **Playwright** for HTML-to-PDF generation
- **JSoup** for HTML sanitization
- **Micrometer** + Prometheus for metrics
- **Lombok** for boilerplate reduction

### Frontend
- **Angular 20** with standalone components and signals
- **Angular Material 20** for UI components
- **SCSS** with CSS custom properties (dark/light themes)
- **Monaco Editor** for code editing
- **ngx-markdown** + **PrismJS** for syntax highlighting
- **@ngrx/signals** for state management
- **Bilingual i18n** (EN/PT-BR)

### Infrastructure
- **Docker** multi-stage builds (Temurin JDK 25)
- **Docker Compose** for full stack orchestration
- **GitHub Actions** CI/CD pipeline
- **Prometheus** + **Grafana** for monitoring

## Project Structure

```
portfolio-blog/
├── src/main/java/dev/catananti/
│   ├── config/          # Security, R2DBC, Redis, WebFlux, i18n configs
│   ├── controller/      # 29 REST controllers (145+ endpoints)
│   ├── dto/             # Request/response DTOs
│   ├── entity/          # R2DBC entities + status enums
│   ├── exception/       # Global exception handler with i18n
│   ├── filter/          # LocaleContext, rate limiting filters
│   ├── repository/      # Reactive R2DBC repositories
│   ├── security/        # JWT provider, auth filters
│   ├── service/         # Business logic services
│   └── util/            # IpAddressExtractor, HtmlSanitizer, etc.
├── src/test/            # 422+ unit/integration tests
├── frontend/
│   ├── src/app/
│   │   ├── core/        # Services (auth, i18n, notifications)
│   │   ├── features/    # Home, Blog, Admin modules
│   │   ├── shared/      # Shared components
│   │   └── models/      # TypeScript interfaces
│   └── src/environments/
├── .github/workflows/   # CI/CD pipeline
├── Dockerfile           # Multi-stage build
├── docker-compose.yml   # Full stack (app, postgres, redis, prometheus, grafana)
└── prometheus.yml       # Metrics scraping config
```

## Prerequisites

- Java 25+
- Maven 3.9+ (wrapper included)
- Node.js 22+ and npm
- Docker and Docker Compose

## Running Locally

### 1. Start dependencies

```bash
docker-compose up -d postgres redis
```

### 2. Run the backend

```bash
./mvnw spring-boot:run
```

The API will be available at `http://localhost:8080`.

### 3. Run the frontend

```bash
cd frontend
npm install
npm start
```

The frontend will be available at `http://localhost:4200` with proxy to the backend.

### 4. Full stack with Docker

```bash
docker-compose up -d
```

This starts: app (8080), PostgreSQL (5432), Redis (6379), Prometheus (9090), Grafana (3000).

## API Endpoints

### Public - Articles & Blog

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/articles` | List published articles (paginated) |
| GET | `/api/v1/articles/{slug}` | Get article by slug |
| GET | `/api/v1/articles/{slug}/related` | Get related articles |
| POST | `/api/v1/articles/{slug}/view` | Record article view |
| POST | `/api/v1/articles/{slug}/like` | Like an article |
| GET | `/api/v1/articles/search?q={query}` | Search articles |
| GET | `/api/v1/articles/tag/{tagSlug}` | Articles by tag |
| GET | `/api/v1/tags` | List all tags |
| GET | `/api/v1/tags/{slug}` | Get tag by slug |
| GET | `/api/v1/search` | Advanced search with filters |
| GET | `/api/v1/search/suggestions` | Autocomplete suggestions |

### Public - Comments

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/articles/{slug}/comments` | Get approved comments |
| GET | `/api/v1/articles/{slug}/comments/count` | Get comment count |
| POST | `/api/v1/articles/{slug}/comments` | Submit comment for moderation |

### Public - Newsletter

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/newsletter/subscribe` | Subscribe to newsletter |
| GET | `/api/v1/newsletter/confirm?token={token}` | Confirm subscription |
| POST | `/api/v1/newsletter/unsubscribe` | Unsubscribe by email |
| GET | `/api/v1/newsletter/unsubscribe?token={token}` | Unsubscribe by token |

### Public - Contact

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/contact` | Send contact message |

### Public - Resume

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/public/resume/{alias}/pdf` | Download resume as PDF |
| GET | `/api/public/resume/{alias}/preview` | Preview resume PDF inline |
| GET | `/api/public/resume/{alias}/html` | Get resume HTML |
| GET | `/api/public/resume/{alias}/profile` | Get resume profile (JSON) |

### Public - Feeds & SEO

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/rss.xml` or `/feed.xml` | RSS feed |
| GET | `/sitemap.xml` | XML sitemap |
| GET | `/api/health` | Health check |
| GET | `/api` | API information |

### Public - Analytics

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/analytics/event` | Track custom event |
| POST | `/api/v1/analytics/view/{slug}` | Track article view |

### Authentication

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/admin/auth/login` | Login |
| POST | `/api/v1/admin/auth/login/v2` | Login with refresh token |
| POST | `/api/v1/admin/auth/refresh` | Refresh access token |
| POST | `/api/v1/admin/auth/logout` | Logout |
| GET | `/api/v1/admin/auth/verify` | Verify token |
| POST | `/api/v1/admin/auth/forgot-password` | Request password reset |
| GET | `/api/v1/admin/auth/reset-password/validate` | Validate reset token |
| POST | `/api/v1/admin/auth/reset-password` | Reset password |

### Admin - Articles

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/articles` | List all articles |
| GET | `/api/v1/admin/articles/{id}` | Get article by ID |
| POST | `/api/v1/admin/articles` | Create article |
| PUT | `/api/v1/admin/articles/{id}` | Update article |
| DELETE | `/api/v1/admin/articles/{id}` | Delete article |
| PATCH | `/api/v1/admin/articles/{id}/publish` | Publish article |
| PATCH | `/api/v1/admin/articles/{id}/unpublish` | Unpublish article |

### Admin - Article Versions

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/articles/{id}/versions` | Version history |
| GET | `/api/v1/admin/articles/{id}/versions/latest` | Latest version |
| GET | `/api/v1/admin/articles/{id}/versions/{num}` | Specific version |
| POST | `/api/v1/admin/articles/{id}/versions/{num}/restore` | Restore version |
| GET | `/api/v1/admin/articles/{id}/versions/compare` | Compare versions |

### Admin - Comments

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/comments` | Get comments by status |
| GET | `/api/v1/admin/comments/article/{articleId}` | Comments by article |
| PUT | `/api/v1/admin/comments/{id}/approve` | Approve comment |
| PUT | `/api/v1/admin/comments/{id}/reject` | Reject comment |
| PUT | `/api/v1/admin/comments/{id}/spam` | Mark as spam |
| DELETE | `/api/v1/admin/comments/{id}` | Delete comment |

### Admin - Tags

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/tags` | List all tags |
| GET | `/api/v1/admin/tags/{id}` | Get tag by ID |
| POST | `/api/v1/admin/tags` | Create tag |
| PUT | `/api/v1/admin/tags/{id}` | Update tag |
| DELETE | `/api/v1/admin/tags/{id}` | Delete tag |

### Admin - Users

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/users` | List users (paginated) |
| GET | `/api/v1/admin/users/me` | Current user |
| GET | `/api/v1/admin/users/{id}` | Get user by ID |
| POST | `/api/v1/admin/users` | Create user |
| PUT | `/api/v1/admin/users/{id}` | Update user |
| PUT | `/api/v1/admin/users/{id}/role` | Update user role |
| DELETE | `/api/v1/admin/users/{id}` | Delete user |
| PUT | `/api/v1/admin/users/{id}/activate` | Activate user |
| PUT | `/api/v1/admin/users/{id}/deactivate` | Deactivate user |
| GET | `/api/v1/admin/users/stats` | User statistics |

### Admin - Dashboard & Analytics

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/dashboard/stats` | Dashboard statistics |
| GET | `/api/v1/admin/dashboard/activity` | Recent activity feed |
| GET | `/api/v1/admin/analytics/summary` | Analytics summary |
| GET | `/api/v1/admin/analytics` | Analytics by period |

### Admin - Newsletter

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/newsletter/subscribers` | List subscribers (paginated) |
| GET | `/api/v1/admin/newsletter/stats` | Newsletter statistics |
| DELETE | `/api/v1/admin/newsletter/subscribers/{id}` | Delete subscriber |
| POST | `/api/v1/admin/newsletter/subscribers/delete-batch` | Batch delete |
| GET | `/api/v1/admin/newsletter/export` | Export subscribers CSV |

### Admin - Contact Messages

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/contact/admin/messages` | List all messages |
| GET | `/api/contact/admin/messages/{id}` | Get message |
| PUT | `/api/contact/admin/messages/{id}/read` | Mark as read |
| DELETE | `/api/contact/admin/messages/{id}` | Delete message |

### Admin - Cache Management

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/cache/stats` | Cache statistics |
| DELETE | `/api/v1/admin/cache/all` | Invalidate all caches |
| DELETE | `/api/v1/admin/cache/articles` | Invalidate article cache |
| DELETE | `/api/v1/admin/cache/tags` | Invalidate tag cache |
| DELETE | `/api/v1/admin/cache/comments` | Invalidate comment cache |
| DELETE | `/api/v1/admin/cache/search` | Invalidate search cache |
| DELETE | `/api/v1/admin/cache/feeds` | Invalidate RSS/sitemap cache |

### Admin - Export/Import

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/export` | Export blog data (JSON) |
| GET | `/api/v1/admin/export/json` | Download as JSON file |
| GET | `/api/v1/admin/export/markdown` | Export as Markdown |
| POST | `/api/v1/admin/export/import` | Import blog data |
| GET | `/api/v1/admin/export/stats` | Export preview stats |

### Admin - Images

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/admin/images/upload` | Upload image |
| DELETE | `/api/v1/admin/images` | Delete image |

### Admin - Settings

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/admin/settings` | Get application settings |
| PUT | `/api/v1/admin/settings` | Update settings |

### Authenticated - Resume Builder

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/resume/profile` | Get resume profile |
| GET | `/api/v1/resume/profile/exists` | Check if profile exists |
| PUT | `/api/v1/resume/profile` | Save/update profile |
| GET | `/api/v1/resume/profile/locales` | Available locales |
| POST | `/api/v1/resume/profile/translate` | Translate profile |
| GET | `/api/v1/resume/profile/generate-html` | Generate HTML |
| GET | `/api/v1/resume/profile/download-html` | Download HTML |
| GET | `/api/v1/resume/profile/download-pdf` | Download PDF |
| POST | `/api/v1/resume/templates` | Create template |
| GET | `/api/v1/resume/templates` | List user templates |
| GET | `/api/v1/resume/templates/{id}` | Get template |
| PUT | `/api/v1/resume/templates/{id}` | Update template |
| DELETE | `/api/v1/resume/templates/{id}` | Delete template |
| POST | `/api/v1/resume/templates/{id}/pdf` | Generate PDF |
| POST | `/api/v1/resume/pdf/generate` | Generate PDF from HTML |

### Health & Metrics

| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/actuator/health` | Health check |
| GET | `/actuator/metrics` | Application metrics |
| GET | `/actuator/prometheus` | Prometheus metrics (Admin only) |

## Authentication

The API uses JWT with HTTP-only cookies. To access protected endpoints:

1. Login at `/api/v1/admin/auth/login`:
```json
{
  "email": "your-admin@example.com",
  "password": "your-secure-password"
}
```

> Configure admin credentials via `ADMIN_EMAIL` and `ADMIN_PASSWORD` environment variables.

2. The JWT token is set as an HTTP-only cookie automatically.

3. For programmatic access, use the `Authorization: Bearer <token>` header.

## Configuration

Key environment variables:

| Variable | Description | Default |
|----------|-------------|---------|
| `JWT_SECRET` | JWT signing secret | (must be set in production) |
| `SPRING_R2DBC_URL` | PostgreSQL R2DBC URL | `r2dbc:postgresql://localhost:5432/blog` |
| `SPRING_DATA_REDIS_HOST` | Redis host | `localhost` |
| `ADMIN_EMAIL` | Admin account email | (required) |
| `ADMIN_PASSWORD` | Admin account password | (required) |
| `CORS_ALLOWED_ORIGINS` | CORS origins | `http://localhost:4200` |
| `APP_SITE_URL` | Public site URL | `https://catananti.dev` |

## Development

### Run tests

```bash
# Backend (422+ tests)
./mvnw test

# Frontend build
cd frontend && npm run build
```

### Build for production

```bash
# Backend JAR
./mvnw clean package -DskipTests

# Docker image
docker build -t portfolio-blog .
```

### CI/CD

GitHub Actions pipeline (`.github/workflows/ci.yml`) runs on push/PR to main:
1. **Backend Tests** - Java 25 + Maven
2. **Frontend Build** - Node 22 + Angular production build
3. **Docker Build** - Verifies container image builds

## License

MIT License
