# Fase 1 — Verificação de e-mail no cadastro

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fazer `users.email_verified` virar `true` por prova de posse do endereço, fechando a lacuna em que qualquer pessoa se cadastra com o e-mail de outra e o sistema trata como legítimo.

**Architecture:** Espelha o `EmailChangeService` já existente: token aleatório de 32 bytes, guardado em SHA-256, enviado em texto no link. Consumo por UPDATE condicional que retorna contagem de linhas, para que duas requisições simultâneas não validem o mesmo token duas vezes. O envio no cadastro é best-effort — falha de SMTP não pode derrubar o registro.

**Tech Stack:** Spring Boot 4 / WebFlux, Spring Data R2DBC, Flyway, PostgreSQL, JUnit 5 + Mockito + StepVerifier.

## Global Constraints

- Backend é **R2DBC reativo, não JPA**. Nada de lazy loading, `JOIN FETCH` ou `@Version` do Hibernate.
- Dentro de transação R2DBC (uma conexão) **não usar `Mono.when` nem `flatMap` concorrente** — usar `concatMap` / `Flux.concat`. Regra documentada em `CommentService.java`.
- `@Transactional` em auto-invocação **não funciona** (proxy). Para escopo de transação dentro da mesma classe, usar `TransactionalOperator`.
- Todo `@Query` que seja INSERT/UPDATE/DELETE precisa de `@Modifying`.
- E-mails em log sempre via `PiiMasker.maskEmail(...)`.
- Mensagens de erro são chaves i18n (`error.foo`), nunca texto literal.
- Migrações V1–V18 têm checksum travado. Esta fase cria a **V19**.
- Mensagens de commit sem menção a ferramentas de IA e sem `Co-Authored-By`.

---

### Task 1: Tabela e entidade do token

**Files:**
- Create: `src/main/resources/db/migration/V19__add_email_verification_tokens.sql`
- Create: `src/main/java/dev/catananti/entity/EmailVerificationToken.java`
- Create: `src/main/java/dev/catananti/repository/EmailVerificationTokenRepository.java`
- Test: `src/test/java/dev/catananti/integration/PersistencePostgresIntegrationTest.java` (adicionar caso)

**Interfaces:**
- Produces: `EmailVerificationToken` (builder com `id`, `userId`, `email`, `token`, `expiresAt`, `used`, `usedAt`, `createdAt`); `EmailVerificationTokenRepository.consumeIfUnused(Long id, LocalDateTime usedAt): Mono<Long>`, `findByTokenAndUsedFalse(String hashedToken): Mono<EmailVerificationToken>`, `countRecentByUserId(Long userId, LocalDateTime since): Mono<Long>`, `deleteExpired(LocalDateTime cutoff): Mono<Long>`

- [ ] **Step 1: Escrever o teste que falha**

Adicionar em `PersistencePostgresIntegrationTest`:

```java
@Test
void emailVerificationTokensTableExistsWithExpectedColumns() {
    Long cols = template.getDatabaseClient()
            .sql("SELECT COUNT(*) AS c FROM information_schema.columns "
               + "WHERE table_name = 'email_verification_tokens' "
               + "AND column_name IN ('id','user_id','email','token','expires_at','used','used_at','created_at')")
            .map(row -> row.get("c", Long.class))
            .one()
            .block(Duration.ofSeconds(10));
    assertThat(cols).isEqualTo(8L);
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=PersistencePostgresIntegrationTest#emailVerificationTokensTableExistsWithExpectedColumns`
Expected: FAIL — `expected 8L but was 0L` (a tabela não existe).

Se o teste for pulado por falta de banco, subir um Postgres e exportar as variáveis:
`docker run -d --name it-pg -e POSTGRES_PASSWORD=test -e POSTGRES_DB=blog -p 15432:5432 postgres:16-alpine`
`export IT_POSTGRES_HOST=localhost IT_POSTGRES_PORT=15432 IT_POSTGRES_DB=blog IT_POSTGRES_USER=postgres IT_POSTGRES_PASSWORD=test`

- [ ] **Step 3: Criar a migração**

`V19__add_email_verification_tokens.sql`:

```sql
-- Verificação de posse do e-mail no cadastro por senha.
-- Até aqui users.email_verified só virava true no fluxo OAuth2, então contas
-- criadas com senha nunca provavam o endereço.
CREATE TABLE IF NOT EXISTS email_verification_tokens (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    email VARCHAR(255) NOT NULL,
    token VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN NOT NULL DEFAULT FALSE,
    used_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Busca é sempre por token; o índice único também barra colisão de hash.
CREATE UNIQUE INDEX IF NOT EXISTS uq_email_verification_tokens_token
    ON email_verification_tokens (token);

-- Rate limit por usuário na última hora.
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_user_created
    ON email_verification_tokens (user_id, created_at DESC);

-- Limpeza periódica varre por expiração e por consumo antigo.
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_cleanup
    ON email_verification_tokens (expires_at, used_at);
```

- [ ] **Step 4: Criar a entidade**

`EmailVerificationToken.java`:

```java
package dev.catananti.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("email_verification_tokens")
public class EmailVerificationToken {

    @Id
    private Long id;

    @Column("user_id")
    private Long userId;

    @Column("email")
    private String email;

    /** SHA-256 do token; o valor em texto só existe no e-mail enviado. */
    @Column("token")
    private String token;

    @Column("expires_at")
    private LocalDateTime expiresAt;

    @Column("used")
    @Builder.Default
    private boolean used = false;

    @Column("used_at")
    private LocalDateTime usedAt;

    @Column("created_at")
    private LocalDateTime createdAt;

    public boolean isValid() {
        return !used && expiresAt != null && expiresAt.isAfter(LocalDateTime.now());
    }
}
```

- [ ] **Step 5: Criar o repositório**

`EmailVerificationTokenRepository.java`:

```java
package dev.catananti.repository;

import dev.catananti.entity.EmailVerificationToken;
import org.springframework.data.r2dbc.repository.Modifying;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;

@Repository
public interface EmailVerificationTokenRepository extends ReactiveCrudRepository<EmailVerificationToken, Long> {

    @Query("SELECT * FROM email_verification_tokens WHERE token = :token AND used = false")
    Mono<EmailVerificationToken> findByTokenAndUsedFalse(String token);

    /**
     * Consome o token de forma atômica. Retorna 1 para quem venceu a corrida e 0
     * para quem chegou depois — duas requisições simultâneas com o mesmo link não
     * podem ambas verificar.
     */
    @Modifying
    @Query("UPDATE email_verification_tokens SET used = true, used_at = :usedAt "
         + "WHERE id = :id AND used = false")
    Mono<Long> consumeIfUnused(Long id, LocalDateTime usedAt);

    @Query("SELECT COUNT(*) FROM email_verification_tokens WHERE user_id = :userId AND created_at > :since")
    Mono<Long> countRecentByUserId(Long userId, LocalDateTime since);

    @Modifying
    @Query("DELETE FROM email_verification_tokens WHERE expires_at < :cutoff OR (used = true AND used_at < :cutoff)")
    Mono<Long> deleteExpired(LocalDateTime cutoff);
}
```

- [ ] **Step 6: Rodar e ver passar**

Run: `./mvnw test -Dtest=PersistencePostgresIntegrationTest`
Expected: PASS, incluindo os 3 casos que já existiam.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V19__add_email_verification_tokens.sql \
        src/main/java/dev/catananti/entity/EmailVerificationToken.java \
        src/main/java/dev/catananti/repository/EmailVerificationTokenRepository.java \
        src/test/java/dev/catananti/integration/PersistencePostgresIntegrationTest.java
git commit -m "feat(auth): add email verification token table"
```

---

### Task 2: Marcar e-mail como verificado

**Files:**
- Modify: `src/main/java/dev/catananti/repository/UserRepository.java`
- Test: `src/test/java/dev/catananti/repository/UserRepositoryQueryTest.java` (criar se não existir)

**Interfaces:**
- Consumes: nada de tasks anteriores.
- Produces: `UserRepository.markEmailVerified(Long userId): Mono<Long>` — retorna linhas afetadas.

- [ ] **Step 1: Escrever o teste que falha**

```java
@Test
void markEmailVerifiedUpdatesOnlyTheFlag() {
    // O UPDATE precisa ser parcial: um save() da entidade inteira sobrescreveria
    // campos que outra requisição alterou no meio do caminho.
    String sql = UserRepository.class.getMethod("markEmailVerified", Long.class)
            .getAnnotation(org.springframework.data.r2dbc.repository.Query.class).value();
    assertThat(sql).contains("SET email_verified = true");
    assertThat(sql).doesNotContain("password_hash");
    assertThat(UserRepository.class.getMethod("markEmailVerified", Long.class)
            .isAnnotationPresent(org.springframework.data.r2dbc.repository.Modifying.class)).isTrue();
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=UserRepositoryQueryTest#markEmailVerifiedUpdatesOnlyTheFlag`
Expected: FAIL com `NoSuchMethodException: markEmailVerified`.

- [ ] **Step 3: Adicionar o método**

Em `UserRepository.java`, junto dos outros UPDATEs parciais já existentes:

```java
    @Modifying
    @Query("UPDATE users SET email_verified = true, updated_at = now() "
         + "WHERE id = :userId AND email_verified = false")
    Mono<Long> markEmailVerified(Long userId);
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=UserRepositoryQueryTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/catananti/repository/UserRepository.java \
        src/test/java/dev/catananti/repository/UserRepositoryQueryTest.java
git commit -m "feat(users): add partial update for email verification flag"
```

---

### Task 3: Envio do e-mail de verificação

**Files:**
- Modify: `src/main/java/dev/catananti/service/EmailService.java`
- Create: `src/main/resources/templates/email/email-verify.html` (copiar a estrutura de `email-change-verify.html`)
- Modify: `src/main/resources/messages.properties` (e as variantes por locale)

**Interfaces:**
- Produces: `EmailService.sendEmailVerification(String email, String name, String token): Mono<Void>`

- [ ] **Step 1: Escrever o teste que falha**

Em `src/test/java/dev/catananti/service/EmailServiceTest.java`:

```java
@Test
void sendEmailVerificationBuildsLinkToVerifyEmailRoute() {
    ReflectionTestUtils.setField(emailService, "siteUrl", "https://catananti.dev");

    StepVerifier.create(emailService.sendEmailVerification("user@test.dev", "Ana", "tok123"))
            .verifyComplete();

    ArgumentCaptor<Map<String, Object>> vars = ArgumentCaptor.forClass(Map.class);
    verify(templateService).render(eq("email-verify"), vars.capture());
    assertThat(vars.getValue().get("verifyUrl"))
            .isEqualTo("https://catananti.dev/auth/verify-email?token=tok123");
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=EmailServiceTest#sendEmailVerificationBuildsLinkToVerifyEmailRoute`
Expected: FAIL — método `sendEmailVerification` não existe.

- [ ] **Step 3: Adicionar o método**

Em `EmailService.java`, seguindo exatamente o formato de `sendEmailChangeVerification` (linha ~810):

```java
    public Mono<Void> sendEmailVerification(String email, String name, String token) {
        String subject = msg("email.verify.subject");
        String verifyUrl = siteUrl + "/auth/verify-email?token=" + token;
        String displayName = name != null ? name : msg("email.default.user");

        String html = templateService.render("email-verify", baseVars(
            "#0ea5e9 0%, #0369a1 100%",
            msg("email.verify.header"),
            Map.of(
                "greeting", msg("email.greeting", displayName),
                "bodyText", msg("email.verify.body"),
                "verifyUrl", verifyUrl,
                "buttonText", msg("email.verify.button"),
                "importantTitle", msg("email.verify.important"),
                "expiresText", msg("email.verify.expires"),
                "onceText", msg("email.verify.once"),
                "ignoreText", msg("email.verify.ignore"),
                "fallbackText", msg("email.verify.fallback")
            )
        ));
        return send(email, subject, html);
    }
```

- [ ] **Step 4: Adicionar as chaves i18n**

Em `messages.properties` (e replicar em `messages_pt_BR.properties`, `messages_es.properties`, `messages_it.properties`):

```properties
email.verify.subject=Confirm your email address
email.verify.header=Confirm your email
email.verify.body=Click the button below to confirm this is your email address.
email.verify.button=Confirm email
email.verify.important=Important
email.verify.expires=This link expires in 24 hours.
email.verify.once=It can only be used once.
email.verify.ignore=If you did not create an account, ignore this message.
email.verify.fallback=If the button does not work, copy this address into your browser:
error.verification_rate_limit=Too many verification emails requested. Try again later.
error.invalid_or_expired_token=This link is invalid or has expired.
```

- [ ] **Step 5: Criar o template**

Copiar `src/main/resources/templates/email/email-change-verify.html` para `email-verify.html`. As variáveis usadas são as mesmas, então o corpo não muda.

- [ ] **Step 6: Rodar e ver passar**

Run: `./mvnw test -Dtest=EmailServiceTest`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add src/main/java/dev/catananti/service/EmailService.java \
        src/main/resources/templates/email/email-verify.html \
        src/main/resources/messages*.properties \
        src/test/java/dev/catananti/service/EmailServiceTest.java
git commit -m "feat(email): add address verification template"
```

---

### Task 4: EmailVerificationService — envio

**Files:**
- Create: `src/main/java/dev/catananti/service/EmailVerificationService.java`
- Test: `src/test/java/dev/catananti/service/EmailVerificationServiceTest.java`

**Interfaces:**
- Consumes: `EmailVerificationTokenRepository` (Task 1), `EmailService.sendEmailVerification` (Task 3).
- Produces: `EmailVerificationService.sendVerification(String email): Mono<Void>`

> **Por que a assinatura recebe só o e-mail:** o `AuthController` expõe o usuário
> logado como `@AuthenticationPrincipal String email` (não existe classe
> `UserPrincipal` neste projeto), e `AuthService.register` devolve `TokenResponse`,
> que **não carrega o id do usuário**. Receber o e-mail e resolver o usuário dentro
> do serviço serve os dois chamadores sem que nenhum precise de uma consulta extra.

- [ ] **Step 1: Escrever os testes que falham**

```java
@ExtendWith(MockitoExtension.class)
class EmailVerificationServiceTest {

    @Mock private EmailVerificationTokenRepository tokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private IdService idService;

    private EmailVerificationService service;

    @BeforeEach
    void setUp() {
        service = new EmailVerificationService(tokenRepository, userRepository, emailService, idService);
        ReflectionTestUtils.setField(service, "tokenValidityHours", 24);
        ReflectionTestUtils.setField(service, "maxTokensPerHour", 3);
    }

    private User user(boolean verified) {
        User u = new User();
        u.setId(10L);
        u.setEmail("user@test.dev");
        u.setName("Ana");
        u.setEmailVerified(verified);
        return u;
    }

    @Test
    void sendVerificationStoresHashedTokenAndEmailsPlainOne() {
        when(userRepository.findByEmail("user@test.dev")).thenReturn(Mono.just(user(false)));
        when(idService.nextId()).thenReturn(1L);
        when(tokenRepository.countRecentByUserId(eq(10L), any())).thenReturn(Mono.just(0L));
        when(tokenRepository.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(emailService.sendEmailVerification(any(), any(), any())).thenReturn(Mono.empty());

        StepVerifier.create(service.sendVerification("user@test.dev")).verifyComplete();

        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(saved.capture());
        ArgumentCaptor<String> mailed = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendEmailVerification(eq("user@test.dev"), eq("Ana"), mailed.capture());

        // o banco guarda o hash, o e-mail leva o texto puro
        assertThat(saved.getValue().getToken()).isNotEqualTo(mailed.getValue());
        assertThat(saved.getValue().getToken()).isEqualTo(DigestUtils.sha256Hex(mailed.getValue()));
    }

    @Test
    void sendVerificationRejectsWhenRateLimitReached() {
        when(userRepository.findByEmail("user@test.dev")).thenReturn(Mono.just(user(false)));
        when(tokenRepository.countRecentByUserId(eq(10L), any())).thenReturn(Mono.just(3L));

        StepVerifier.create(service.sendVerification("user@test.dev"))
                .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
                .verify();

        verify(tokenRepository, never()).save(any());
    }

    @Test
    void sendVerificationIsNoOpWhenAlreadyVerified() {
        // Contas OAuth2 já nascem verificadas; reenviar seria ruído e um vetor
        // barato de spam contra o endereço de terceiros.
        when(userRepository.findByEmail("user@test.dev")).thenReturn(Mono.just(user(true)));

        StepVerifier.create(service.sendVerification("user@test.dev")).verifyComplete();

        verify(tokenRepository, never()).save(any());
        verify(emailService, never()).sendEmailVerification(any(), any(), any());
    }

    @Test
    void sendVerificationIsSilentForUnknownEmail() {
        // Não revelar se o endereço existe: a resposta é idêntica nos dois casos.
        when(userRepository.findByEmail("nobody@test.dev")).thenReturn(Mono.empty());

        StepVerifier.create(service.sendVerification("nobody@test.dev")).verifyComplete();

        verify(tokenRepository, never()).save(any());
    }

    @Test
    void sendVerificationSurvivesSmtpFailure() {
        // O token fica salvo para reenvio; falha de SMTP não pode propagar,
        // senão derruba o cadastro do usuário.
        when(userRepository.findByEmail("user@test.dev")).thenReturn(Mono.just(user(false)));
        when(idService.nextId()).thenReturn(1L);
        when(tokenRepository.countRecentByUserId(eq(10L), any())).thenReturn(Mono.just(0L));
        when(tokenRepository.save(any())).thenAnswer(i -> Mono.just(i.getArgument(0)));
        when(emailService.sendEmailVerification(any(), any(), any()))
                .thenReturn(Mono.error(new RuntimeException("smtp down")));

        StepVerifier.create(service.sendVerification("user@test.dev")).verifyComplete();
    }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=EmailVerificationServiceTest`
Expected: FAIL — classe `EmailVerificationService` não existe.

- [ ] **Step 3: Implementar**

```java
package dev.catananti.service;

import dev.catananti.entity.EmailVerificationToken;
import dev.catananti.repository.EmailVerificationTokenRepository;
import dev.catananti.repository.UserRepository;
import dev.catananti.util.DigestUtils;
import dev.catananti.util.PiiMasker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationService {

    private final EmailVerificationTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;
    private final IdService idService;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int TOKEN_BYTES = 32;

    @Value("${app.email-verification.token-validity-hours:24}")
    private int tokenValidityHours;

    @Value("${app.email-verification.max-tokens-per-hour:3}")
    private int maxTokensPerHour;

    /**
     * Emite e envia um token de verificação para o endereço informado.
     *
     * Silencioso quando o e-mail não existe ou já está verificado: a resposta é
     * idêntica nos dois casos, para o endpoint não virar um oráculo de quais
     * endereços têm conta.
     */
    public Mono<Void> sendVerification(String email) {
        String normalized = email == null ? "" : email.strip().toLowerCase();

        return userRepository.findByEmail(normalized)
                .filter(user -> !Boolean.TRUE.equals(user.getEmailVerified()))
                .flatMap(user -> issueAndSend(user.getId(), normalized, user.getName()))
                .then();
    }

    private Mono<Void> issueAndSend(Long userId, String email, String name) {
        LocalDateTime oneHourAgo = LocalDateTime.now().minusHours(1);

        return tokenRepository.countRecentByUserId(userId, oneHourAgo)
                .flatMap(recent -> {
                    if (recent >= maxTokensPerHour) {
                        log.warn("Email verification rate limit hit for userId: {}", userId);
                        return Mono.error(new ResponseStatusException(
                                HttpStatus.TOO_MANY_REQUESTS, "error.verification_rate_limit"));
                    }

                    byte[] raw = new byte[TOKEN_BYTES];
                    SECURE_RANDOM.nextBytes(raw);
                    String plainToken = ENCODER.encodeToString(raw);

                    EmailVerificationToken token = EmailVerificationToken.builder()
                            .id(idService.nextId())
                            .userId(userId)
                            .email(email)
                            .token(DigestUtils.sha256Hex(plainToken))
                            .expiresAt(LocalDateTime.now().plus(Duration.ofHours(tokenValidityHours)))
                            .used(false)
                            .createdAt(LocalDateTime.now())
                            .build();

                    return tokenRepository.save(token)
                            .flatMap(saved -> emailService.sendEmailVerification(email, name, plainToken))
                            // O token fica salvo para reenvio. Propagar o erro derrubaria
                            // o cadastro por uma falha de SMTP.
                            .onErrorResume(e -> {
                                log.warn("Verification email failed for {} (token kept for resend): {}",
                                        PiiMasker.maskEmail(email), e.getMessage());
                                return Mono.empty();
                            });
                })
                .then();
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=EmailVerificationServiceTest`
Expected: PASS nos 3 testes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/catananti/service/EmailVerificationService.java \
        src/test/java/dev/catananti/service/EmailVerificationServiceTest.java
git commit -m "feat(auth): send email verification tokens"
```

---

### Task 5: EmailVerificationService — consumo

**Files:**
- Modify: `src/main/java/dev/catananti/service/EmailVerificationService.java`
- Modify: `src/test/java/dev/catananti/service/EmailVerificationServiceTest.java`

**Interfaces:**
- Consumes: `consumeIfUnused` (Task 1), `markEmailVerified` (Task 2).
- Produces: `EmailVerificationService.verify(String plainToken): Mono<Long>` — devolve o `userId` verificado, para a Fase 2 pendurar o vínculo aqui.

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test
void verifyMarksUserAndReturnsUserId() {
    String plain = "tok-abc";
    EmailVerificationToken token = EmailVerificationToken.builder()
            .id(5L).userId(10L).email("user@test.dev")
            .token(DigestUtils.sha256Hex(plain))
            .expiresAt(LocalDateTime.now().plusHours(1))
            .used(false).build();

    when(tokenRepository.findByTokenAndUsedFalse(DigestUtils.sha256Hex(plain))).thenReturn(Mono.just(token));
    when(tokenRepository.consumeIfUnused(eq(5L), any())).thenReturn(Mono.just(1L));
    when(userRepository.markEmailVerified(10L)).thenReturn(Mono.just(1L));

    StepVerifier.create(service.verify(plain)).expectNext(10L).verifyComplete();
    verify(userRepository).markEmailVerified(10L);
}

@Test
void verifyRejectsTokenAlreadyConsumedByAConcurrentRequest() {
    // Duas abas abrindo o mesmo link: quem perde a corrida recebe 0 linhas
    // e não pode verificar de novo.
    String plain = "tok-abc";
    EmailVerificationToken token = EmailVerificationToken.builder()
            .id(5L).userId(10L).email("user@test.dev")
            .token(DigestUtils.sha256Hex(plain))
            .expiresAt(LocalDateTime.now().plusHours(1))
            .used(false).build();

    when(tokenRepository.findByTokenAndUsedFalse(any())).thenReturn(Mono.just(token));
    when(tokenRepository.consumeIfUnused(eq(5L), any())).thenReturn(Mono.just(0L));

    StepVerifier.create(service.verify(plain))
            .expectErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST))
            .verify();

    verify(userRepository, never()).markEmailVerified(any());
}

@Test
void verifyRejectsExpiredToken() {
    EmailVerificationToken expired = EmailVerificationToken.builder()
            .id(5L).userId(10L).token(DigestUtils.sha256Hex("t"))
            .expiresAt(LocalDateTime.now().minusMinutes(1))
            .used(false).build();
    when(tokenRepository.findByTokenAndUsedFalse(any())).thenReturn(Mono.just(expired));

    StepVerifier.create(service.verify("t"))
            .expectError(ResponseStatusException.class)
            .verify();

    verify(tokenRepository, never()).consumeIfUnused(any(), any());
}

@Test
void verifyRejectsUnknownToken() {
    when(tokenRepository.findByTokenAndUsedFalse(any())).thenReturn(Mono.empty());
    StepVerifier.create(service.verify("nope"))
            .expectError(ResponseStatusException.class)
            .verify();
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=EmailVerificationServiceTest`
Expected: FAIL — método `verify` não existe.

- [ ] **Step 3: Implementar**

```java
    /**
     * Consome o token e marca o e-mail como verificado. Devolve o id do usuário.
     *
     * O consumo é um UPDATE condicional que retorna contagem de linhas: quem
     * obtém 0 perdeu a corrida para outra requisição e não pode verificar.
     * Um `if (token.isUsed())` em Java passaria no teste e falharia em produção.
     */
    public Mono<Long> verify(String plainToken) {
        String hashed = DigestUtils.sha256Hex(plainToken);

        return tokenRepository.findByTokenAndUsedFalse(hashed)
                .filter(EmailVerificationToken::isValid)
                .switchIfEmpty(Mono.error(new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "error.invalid_or_expired_token")))
                .flatMap(token -> tokenRepository.consumeIfUnused(token.getId(), LocalDateTime.now())
                        .flatMap(rows -> {
                            if (rows == 0) {
                                return Mono.error(new ResponseStatusException(
                                        HttpStatus.BAD_REQUEST, "error.invalid_or_expired_token"));
                            }
                            return userRepository.markEmailVerified(token.getUserId())
                                    .thenReturn(token.getUserId());
                        }))
                .doOnSuccess(userId -> log.info("Email verified for userId: {}", userId));
    }
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=EmailVerificationServiceTest`
Expected: PASS nos 7 testes.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/catananti/service/EmailVerificationService.java \
        src/test/java/dev/catananti/service/EmailVerificationServiceTest.java
git commit -m "feat(auth): consume verification token atomically"
```

---

### Task 6: Endpoints

**Files:**
- Modify: `src/main/java/dev/catananti/controller/AuthController.java`
- Modify: `src/main/java/dev/catananti/config/SecurityConfig.java`
- Test: `src/test/java/dev/catananti/controller/AuthControllerTest.java`

**Interfaces:**
- Consumes: `EmailVerificationService.verify` e `sendVerification` (Tasks 4 e 5).
- Produces: `GET /api/v1/admin/auth/verify-email?token=`, `POST /api/v1/admin/auth/resend-verification`

- [ ] **Step 1: Escrever o teste que falha**

```java
@Test
void verifyEmailReturnsOkOnValidToken() {
    when(emailVerificationService.verify("tok")).thenReturn(Mono.just(10L));

    StepVerifier.create(authController.verifyEmail("tok"))
            .assertNext(resp -> assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK))
            .verifyComplete();
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=AuthControllerTest#verifyEmailReturnsOkOnValidToken`
Expected: FAIL — método `verifyEmail` não existe.

- [ ] **Step 3: Adicionar os endpoints**

Em `AuthController.java`, ao lado de `/verify-email-change` (linha ~307):

```java
    @GetMapping("/verify-email")
    public Mono<ResponseEntity<Map<String, String>>> verifyEmail(@RequestParam String token) {
        return emailVerificationService.verify(token)
                .map(userId -> ResponseEntity.ok(Map.of("message", "email.verified")));
    }

    @PostMapping("/resend-verification")
    public Mono<ResponseEntity<Map<String, String>>> resendVerification(
            @AuthenticationPrincipal String email) {
        return emailVerificationService.sendVerification(email)
                .thenReturn(ResponseEntity.accepted().body(Map.of("message", "email.verification_sent")));
    }
```

Adicionar `private final EmailVerificationService emailVerificationService;` ao construtor.

- [ ] **Step 4: Liberar a rota pública**

Em `SecurityConfig.java`, junto de `/verify-email-change`, adicionar `"/api/v1/admin/auth/verify-email"` à lista de rotas permitidas sem autenticação. O `resend-verification` **continua exigindo autenticação** — só quem está logado pede reenvio para o próprio endereço.

- [ ] **Step 5: Rodar e ver passar**

Run: `./mvnw test -Dtest=AuthControllerTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/catananti/controller/AuthController.java \
        src/main/java/dev/catananti/config/SecurityConfig.java \
        src/test/java/dev/catananti/controller/AuthControllerTest.java
git commit -m "feat(auth): expose email verification endpoints"
```

---

### Task 7: Disparar no cadastro

**Files:**
- Modify: `src/main/java/dev/catananti/service/AuthService.java`
- Test: `src/test/java/dev/catananti/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `EmailVerificationService.sendVerification` (Task 4).

- [ ] **Step 1: Escrever os testes que falham**

```java
@Test
void registerSendsVerificationEmail() {
    // reaproveita o arrange de registro que já existe na classe de teste
    when(emailVerificationService.sendVerification(anyString())).thenReturn(Mono.empty());

    StepVerifier.create(authService.register(request, "127.0.0.1"))
            .expectNextCount(1).verifyComplete();

    verify(emailVerificationService).sendVerification("novo@test.dev");
}

@Test
void registerSucceedsEvenIfVerificationEmailFails() {
    // O envio acontece FORA da transação e é best-effort: a conta não pode
    // deixar de ser criada porque o SMTP caiu.
    when(emailVerificationService.sendVerification(anyString()))
            .thenReturn(Mono.error(new RuntimeException("smtp down")));

    StepVerifier.create(authService.register(request, "127.0.0.1"))
            .expectNextCount(1).verifyComplete();
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=AuthServiceTest#registerSendsVerificationEmail`
Expected: FAIL — `Wanted but not invoked: emailVerificationService.sendVerification`.

- [ ] **Step 3: Implementar**

Assinatura real: `public Mono<TokenResponse> register(RegisterRequest request, String clientIp)`.
`TokenResponse` **não expõe o id do usuário** — por isso a Task 4 recebe o e-mail e
resolve o usuário internamente.

Em `AuthService.register`, **depois** do `transactionalOperator.transactional(persistNewUser(...))` —
nunca dentro dele, porque SMTP dentro de transação segura conexão do pool durante
todo o handshake com o servidor de e-mail:

```java
                .flatMap(response -> emailVerificationService.sendVerification(normalizedEmail)
                        .onErrorResume(e -> {
                            log.warn("Verification email failed at registration: {}", e.getMessage());
                            return Mono.empty();
                        })
                        .thenReturn(response))
```

Adicionar `private final EmailVerificationService emailVerificationService;` ao
construtor do `AuthService` e o `@Mock` correspondente no `AuthServiceTest`.
O construtor é escrito à mão (não usa `@RequiredArgsConstructor`), então o parâmetro
precisa ser inserido na lista **antes** dos parâmetros `@Autowired(required = false)`.

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=AuthServiceTest`
Expected: PASS

- [ ] **Step 5: Rodar a suíte inteira**

Run: `./mvnw test`
Expected: PASS. A base era 1854 testes; esta fase adiciona ~12.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/dev/catananti/service/AuthService.java \
        src/test/java/dev/catananti/service/AuthServiceTest.java
git commit -m "feat(auth): send verification email on registration"
```

---

### Task 8: Limpeza de tokens expirados

**Files:**
- Create: `src/main/java/dev/catananti/scheduler/EmailVerificationCleanupScheduler.java`
- Test: `src/test/java/dev/catananti/scheduler/EmailVerificationCleanupSchedulerTest.java`

**Interfaces:**
- Consumes: `EmailVerificationTokenRepository.deleteExpired` (Task 1), `SchedulerLock`.

- [ ] **Step 1: Escrever o teste que falha**

```java
@Test
void cleanupDeletesExpiredTokens() {
    when(schedulerLock.executeWithLock(anyString(), any(), any()))
            .thenAnswer(i -> i.getArgument(2));
    when(tokenRepository.deleteExpired(any())).thenReturn(Mono.just(7L));

    StepVerifier.create(scheduler.cleanup()).verifyComplete();
    verify(tokenRepository).deleteExpired(any());
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `./mvnw test -Dtest=EmailVerificationCleanupSchedulerTest`
Expected: FAIL — classe não existe.

- [ ] **Step 3: Implementar**

Espelhar `RefreshTokenService.cleanupExpiredTokens`: método público devolvendo `Mono<Void>` sob `schedulerLock`, e um método `void ...Scheduled()` separado anotado com `@Scheduled` que só faz `.subscribe()`.

**Nunca anotar um `void` com `@Scheduled` numa classe que tenha `@PreAuthorize`** — o interceptor reativo do Spring Security exige retorno `Publisher` e o método explode em runtime todo dia. Foi exatamente esse o bug encontrado no `AdminAuditController`.

```java
package dev.catananti.scheduler;

import dev.catananti.repository.EmailVerificationTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class EmailVerificationCleanupScheduler {

    private final EmailVerificationTokenRepository tokenRepository;
    private final SchedulerLock schedulerLock;

    public Mono<Void> cleanup() {
        return schedulerLock.executeWithLock("email-verification-cleanup", Duration.ofMinutes(5),
                tokenRepository.deleteExpired(LocalDateTime.now().minusDays(7))
                        .doOnSuccess(n -> log.info("Deleted {} expired verification tokens", n))
                        .onErrorResume(e -> {
                            log.error("Verification token cleanup failed", e);
                            return Mono.empty();
                        })
                        .then());
    }

    @Scheduled(fixedRateString = "${scheduling.email-verification-cleanup-ms:86400000}",
               initialDelayString = "${scheduling.initial-delay-ms:30000}")
    public void cleanupScheduled() {
        cleanup().subscribe();
    }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: `./mvnw test -Dtest=EmailVerificationCleanupSchedulerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/dev/catananti/scheduler/EmailVerificationCleanupScheduler.java \
        src/test/java/dev/catananti/scheduler/EmailVerificationCleanupSchedulerTest.java
git commit -m "feat(auth): purge expired verification tokens"
```

---

### Task 9: Tela de verificação no frontend

**Files (repo `portfolio-blog-frontend`):**
- Create: `src/app/features/auth/pages/verify-email/verify-email.component.ts`
- Modify: `src/app/features/auth/auth.routes.ts`
- Modify: `src/app/core/services/i18n/{en,es,it,pt-br}.ts`

- [ ] **Step 1: Escrever o teste que falha**

```typescript
it('calls the API with the token from the query string and shows success', async () => {
  await TestBed.configureTestingModule({
    imports: [VerifyEmailComponent],
    providers: [
      provideHttpClientTesting(),
      { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: convertToParamMap({ token: 'tok' }) } } },
    ],
  }).compileComponents();

  const fixture = TestBed.createComponent(VerifyEmailComponent);
  fixture.detectChanges();

  const req = TestBed.inject(HttpTestingController)
      .expectOne(r => r.url.endsWith('/admin/auth/verify-email') && r.params.get('token') === 'tok');
  req.flush({ message: 'email.verified' });
  fixture.detectChanges();

  expect(fixture.componentInstance.state()).toBe('success');
});
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `npm test -- --include='**/verify-email.component.spec.ts'`
Expected: FAIL — componente não existe.

- [ ] **Step 3: Implementar**

`verify-email.component.ts`:

```typescript
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { ApiService } from '../../../../core/services/api.service';
import { TranslatePipe } from '../../../../core/services/i18n/translate.pipe';

type State = 'loading' | 'success' | 'error';

@Component({
  selector: 'app-verify-email',
  standalone: true,
  imports: [RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="verify">
      @switch (state()) {
        @case ('loading') {
          <p role="status">{{ 'auth.verifyEmail.loading' | translate }}</p>
        }
        @case ('success') {
          <h1>{{ 'auth.verifyEmail.successTitle' | translate }}</h1>
          <a routerLink="/auth/login">{{ 'auth.verifyEmail.goToLogin' | translate }}</a>
        }
        @case ('error') {
          <h1>{{ 'auth.verifyEmail.errorTitle' | translate }}</h1>
          <p role="alert">{{ 'auth.verifyEmail.errorBody' | translate }}</p>
        }
      }
    </section>
  `,
})
export class VerifyEmailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly api = inject(ApiService);

  readonly state = signal<State>('loading');

  ngOnInit(): void {
    const token = this.route.snapshot.queryParamMap.get('token');
    if (!token) {
      this.state.set('error');
      return;
    }
    this.api.get('/admin/auth/verify-email', { params: { token } }).subscribe({
      next: () => this.state.set('success'),
      error: () => this.state.set('error'),
    });
  }
}
```

Rota em `auth.routes.ts`:

```typescript
  {
    path: 'verify-email',
    loadComponent: () =>
      import('./pages/verify-email/verify-email.component').then((m) => m.VerifyEmailComponent),
  },
```

O caminho `auth/verify-email` **precisa bater exatamente** com a URL montada no
`EmailService` da Task 3 (`siteUrl + "/auth/verify-email?token="`). Divergência aqui
produz um link que abre uma página 404 — e o sintoma aparece só no e-mail real,
nunca nos testes.

Chaves i18n a adicionar nos quatro locales:

```typescript
    'auth.verifyEmail.loading': 'Confirming your email…',
    'auth.verifyEmail.successTitle': 'Email confirmed',
    'auth.verifyEmail.goToLogin': 'Go to login',
    'auth.verifyEmail.errorTitle': 'Could not confirm',
    'auth.verifyEmail.errorBody': 'This link is invalid or has expired. Request a new one from your account.',
```

- [ ] **Step 4: Rodar e ver passar**

Run: `npm test -- --include='**/verify-email.component.spec.ts'`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/app/features/auth/pages/verify-email/ src/app/features/auth/auth.routes.ts src/app/core/services/i18n/
git commit -m "feat(auth): add email verification page"
```

---

## O que esta fase deliberadamente não faz

- **Não bloqueia o login de quem não verificou.** Bloquear mudaria o comportamento de todas as contas existentes de uma vez, inclusive as de produção, que hoje têm `email_verified = false`. A verificação gateia apenas o que exige e-mail provado — a partir da Fase 2, o vínculo com a newsletter.
- **Não envia verificação para contas OAuth2.** Elas já nascem com `email_verified = true`, vindo do provedor.
- **Não faz backfill.** Contas existentes continuam não verificadas até pedirem reenvio.

## Próximas fases

- **Fase 2** (vínculo newsletter ↔ conta) — migração V20, pendura o `NewsletterLinkService` no `verify()` da Task 5.
- **Fase 3** (desativação e eliminação de conta) — migração V21, inclui `comments.user_id` e a cascata da spec.
