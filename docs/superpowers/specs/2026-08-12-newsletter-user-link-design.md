# Vínculo entre inscrito na newsletter e conta de usuário

Data: 2026-08-12

## Problema

`subscribers` e `users` são hoje tabelas completamente independentes. Não existe
nenhuma ligação entre elas, nem em código nem em banco. Isso produz três efeitos:

1. Um usuário logado não consegue ver nem gerenciar sua inscrição na newsletter —
   depende do link com token que veio por e-mail.
2. O consentimento de analytics de navegação vive apenas no cliente (header
   `X-Analytics-Consent`, sem persistência), então a escolha do titular se perde a
   cada troca de dispositivo ou limpeza de navegador.
3. O painel administrativo não tem como saber quantos inscritos viraram usuários.

## Escopo

**Dentro:** gestão da inscrição pela área da conta; unificação de idioma e
consentimento; visibilidade do vínculo no admin.

**Fora, por decisão explícita:** segmentação de envio por atividade do usuário
(artigos lidos, bookmarks, histórico). Foi descartada por ser a parte mais
invasiva em privacidade e não necessária para os objetivos acima.

A LGPD é tratada como restrição de projeto, não como item de checklist.

## Decisões

| Tema | Decisão |
|---|---|
| Gatilho do vínculo automático | Só quando **ambos os lados provaram o e-mail**: `users.email_verified = true` **e** `subscriber.status = CONFIRMED` |
| Transparência | O vínculo é informado na área da conta e pode ser desfeito pelo titular |
| Consentimento | **Dois consentimentos separados por finalidade**, apresentados numa tela só |
| Exclusão de conta | A tela de exclusão pergunta explicitamente se a inscrição deve ser cancelada junto |
| Modelagem | Colunas em `subscribers` (não tabela de junção) |

### Por que dois consentimentos e não um

São finalidades distintas:

- `subscribers.analytics_consent` (criado pela V7, que cita "LGPD Art. 7-I") cobre
  rastreio de **abertura e clique no e-mail**.
- `X-Analytics-Consent` cobre analytics de **navegação no site**.

O art. 8 §4 exige consentimento específico por finalidade. Um flag único
reaproveitaria o consentimento dado para uma finalidade em outra.

### Por que colunas e não tabela de junção

A relação é 1:1 (um e-mail, um inscrito, uma conta) e o backend é R2DBC — sem
lazy loading, todo join é manual e explícito, então uma tabela de junção custaria
código em toda consulta para modelar algo que não é N:N. Se o cenário
multi-e-mail-por-conta aparecer, a migração para tabela de junção é mecânica.

## Modelo de dados

Migração **V20** (a V19 é da Fase 1). Depende da V18 — ver Pré-requisitos.

### `subscribers`

| Coluna | Tipo | Papel |
|---|---|---|
| `user_id` | `BIGINT NULL` → `users(id) ON DELETE SET NULL` | O vínculo |
| `linked_at` | `TIMESTAMP NULL` | Quando foi criado |
| `link_origin` | `VARCHAR(32) NULL` | `AUTO_REGISTER`, `AUTO_SUBSCRIBE`, `AUTO_BACKFILL`, `MANUAL_USER`, `MANUAL_ADMIN` |
| `unlinked_at` | `TIMESTAMP NULL` | Quando foi desfeito |
| `unlinked_by` | `VARCHAR(16) NULL` | `USER`, `ADMIN`, `ACCOUNT_DELETED` |

```sql
CREATE UNIQUE INDEX uq_subscribers_user_id
    ON subscribers (user_id) WHERE user_id IS NOT NULL;
```

O índice parcial garante o 1:1 sem impedir que existam muitos inscritos sem conta.

Os quatro estados são distinguíveis, e a distinção tem consequência:

```
nunca vinculado    user_id NULL   linked_at NULL   unlinked_at NULL
vinculado          user_id SET    linked_at SET    unlinked_at NULL
desvinculado       user_id NULL   linked_at SET    unlinked_at SET   unlinked_by USER
conta apagada      user_id NULL   linked_at SET    unlinked_at SET   unlinked_by ACCOUNT_DELETED
```

`unlinked_at` responde *quando*; `unlinked_by` é o que decide *se pode voltar*.
Apenas a recusa do próprio titular (`USER`) bloqueia o **re-vínculo automático**.
Se o vínculo caiu porque a conta foi apagada ou porque um administrador interveio,
e a pessoa criar conta de novo com o mesmo e-mail, o vínculo se refaz normalmente.

O bloqueio vale só para o caminho automático. Quando o próprio titular pede o
vínculo de volta pela área da conta, a recusa anterior é justamente o que ele está
revogando — ver "Duas operações distintas" abaixo.

O `ON DELETE SET NULL` da FK é **rede de segurança, não o mecanismo principal**:
ele zera `user_id` mas não preenche as colunas de auditoria. Quem escreve
`unlinked_at` e `unlinked_by = 'ACCOUNT_DELETED'` é o fluxo de exclusão da Fase 3.
Se uma linha de `users` for apagada por fora, sobra o estado órfão
(`user_id NULL`, `linked_at SET`, `unlinked_at NULL`), que é tratado como
re-vinculável — o mesmo que "nunca vinculado" para efeito de decisão.

### `users`

| Coluna | Tipo | Papel |
|---|---|---|
| `analytics_consent` | `BOOLEAN NULL` | `NULL` = nunca decidiu; `FALSE` = recusou |
| `analytics_consent_at` | `TIMESTAMP NULL` | Prova de quando |
| `status` | `VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'` | `ACTIVE`, `DEACTIVATED`, `ERASED` (Fase 3, V21) |
| `deleted_at` | `TIMESTAMP NULL` | Quando a conta saiu do ar (Fase 3, V21) |

A distinção entre `NULL` e `FALSE` no consentimento é necessária: sem ela não há
como diferenciar quem recusou de quem nunca viu o aviso, e recusar é uma decisão
que não se pode ficar reperguntando.

`status` convive com o `active` booleano já existente em vez de substituí-lo:
`active` continua sendo o que o login consulta (evita mexer em caminho crítico), e
`status` carrega a distinção entre desativado e eliminado, que `active` não expressa.

### `comments`

| Coluna | Tipo | Papel |
|---|---|---|
| `user_id` | `BIGINT NULL` → `users(id) ON DELETE SET NULL` | Autoria estrutural (ver Fase 3) |

Backfill por `LOWER(author_email) = LOWER(users.email)`. Fica `NULL` para
comentários de visitantes não cadastrados, que continuam permitidos.

## Fases

As três são independentemente entregáveis e devem sair nesta ordem.

### Fase 1 — Verificação de e-mail no cadastro

Hoje `email_verified` só vira `true` no `OAuth2Service`. O `UserService` documenta
que o fluxo do cadastro por senha existe no schema mas nunca foi habilitado. Isso
é uma lacuna de segurança que já existe independente desta feature: qualquer pessoa
se cadastra com o e-mail de outra e o sistema trata como legítimo.

Sem esta fase, a regra de vínculo escolhida atenderia apenas usuários de login
social.

**Entrega:** tabela `email_verification_tokens` e `EmailVerificationService`
espelhando o `EmailChangeService` já existente (mesmo hash de token, mesmo rate
limiting por e-mail, mesma expiração). Endpoint de verificação e de reenvio. O
registro passa a disparar o envio. Tela de verificação no frontend.

**Decisão de compatibilidade:** usuário não verificado **continua conseguindo
logar**. Bloquear o login mudaria o comportamento de todas as contas existentes de
uma vez — inclusive as de produção, que têm `email_verified = false`. A verificação
gateia apenas o que exige e-mail provado, não o acesso.

### Fase 2 — O vínculo

**Entrega:** migração V20, `NewsletterLinkService`, ganchos nos dois fluxos,
backfill, coluna de consentimento em `users`, tela única de consentimento na conta,
indicação de vínculo no `SubscriberResponse` do admin.

### Fase 3 — Exclusão de conta

Não existe hoje nenhum endpoint de exclusão de conta, nem `UserController` nem
`AccountController`. O direito à eliminação (art. 18) não tem atendimento
automatizado.

**Entrega:** `AccountService` e endpoint de exclusão com **reautenticação
obrigatória** antes. A tela informa a inscrição vinculada e oferece cancelá-la
junto, com escolha explícita.

#### Duas operações, não uma

Manter a identidade do autor ligada ao conteúdo **não é eliminação sob a LGPD**:
dado que permite reidentificar o titular continua sendo dado pessoal (art. 5, XI).
Chamar isso de "excluir conta" atenderia mal um pedido formal do art. 18, VI.

São dois níveis, e a interface precisa nomeá-los honestamente:

**Nível 1 — Desativação.** A conta some do ar, o conteúdo público permanece com
autoria preservada, a rastreabilidade interna é total. Reversível. É o
comportamento padrão do botão na área da conta.

**Nível 2 — Eliminação.** Atende ao art. 18, VI. Além de tudo do nível 1, a PII da
linha de `users` é anonimizada. O `user_id` nas tabelas de conteúdo **permanece** —
passa a apontar para um registro que não reidentifica ninguém, o que preserva
integridade referencial e estatística sem manter dado pessoal (art. 16, IV).
Irreversível.

#### Pré-requisito: `comments.user_id`

`comments` não tem FK para `users`. Guarda `author_name` e `author_email` como
texto denormalizado, então **hoje não existe rastreio estrutural de autoria** — o
único elo é a string do e-mail. Há também PII em texto puro numa tabela de conteúdo
público.

A V21 adiciona `comments.user_id BIGINT NULL REFERENCES users(id) ON DELETE SET NULL`
com backfill por `LOWER(author_email) = LOWER(users.email)`. Isso é o que torna
possível, ao mesmo tempo, preservar "qual usuário comentou" e remover o e-mail da
tabela pública.

#### Cascata por tabela

| Tabela | Desativação | Eliminação |
|---|---|---|
| `users` | `active=false`, `status=DELETED`, `deleted_at` | + PII anonimizada (email→hash, name→"Usuário removido", avatar/bio/username nulos) |
| `comments` | intacto, autoria preservada | `author_name`→"Usuário removido", `author_email`→NULL; **`user_id` mantido** |
| `articles` | intacto (`author_id` preservado) | `author_id` mantido, apontando para o registro anonimizado |
| `refresh_tokens` | **todos revogados** | apagados |
| `password_reset_tokens`, `email_change_tokens` | apagados | apagados |
| `user_mfa_config`, `mfa_backup_codes` | apagados | apagados |
| `user_social_accounts` | apagados (desconecta provedores) | apagados |
| `bookmarks`, `reading_history` | apagados — dado privado, sem valor público | apagados |
| `search_queries` | `user_id`→NULL, preserva o agregado | idem |
| `role_upgrade_requests` | mantidos (histórico administrativo) | `user_id` mantido; PII já anonimizada em `users` |
| `audit_logs` | **mantidos** | **mantidos** — retenção por obrigação de trilha (art. 16, I) |
| `subscribers` | conforme escolha do titular na tela | idem |

`analytics_events` não tem `user_id` (é indexado por hash de visitante), então não
entra na cascata.

**Atenção às FKs existentes:** hoje há 21 `ON DELETE CASCADE` apontando para
`users`. Como nenhuma das duas operações apaga a linha de `users`, essas cascatas
**não disparam** — as remoções acima são explícitas na aplicação. A FK segue como
rede de segurança para exclusão física feita por fora.

## Os três momentos do vínculo

**Inscrito que vira usuário.** O gancho é o **momento em que o e-mail é
verificado**, não o registro. No fluxo OAuth2 isso é imediato (o provedor já
entrega verificado); no cadastro por senha é quando o token da Fase 1 é
consumido. Pendurar no registro pareceria natural e criaria exatamente o furo que
a regra de vínculo quer evitar.

**Usuário que se inscreve depois.** O gancho é `confirmSubscription`, não
`subscribe` — é a confirmação que prova a posse do endereço.

**Pares já existentes.** Passo idempotente na própria V20, casando
`LOWER(users.email) = subscribers.email` sob as mesmas condições, com
`link_origin = 'AUTO_BACKFILL'`. Precisa ser idempotente porque pode ser
reexecutado num restore.

## Componentes

**`NewsletterLinkService`** — recebe `(userId, subscriberId)` e cria ou desfaz o
vínculo. Não conhece e-mail nem HTTP: quem descobre o par é quem chama. Mantém a
regra de vínculo num lugar só e torna o serviço testável isoladamente.

**`EmailVerificationService`** — ciclo de vida do token. Ao verificar, seta
`email_verified = true` e chama o link service.

**`AccountService`** — exclusão com reautenticação.

A comunicação entre eles é por **chamada direta** dentro do fluxo reativo. O
`ApplicationEventPublisher` do Spring é síncrono e não compõe com `Mono`, o que
tornaria o encadeamento frágil e difícil de testar.

## Erros e concorrência

**O vínculo é best-effort e nunca derruba o fluxo principal.** Se falhar durante a
verificação de e-mail, a verificação ainda precisa suceder — `onErrorResume` com
log. O contrário seria não verificar o e-mail da pessoa porque a newsletter falhou.

**A corrida é real:** o titular pode confirmar a inscrição e verificar o e-mail
quase ao mesmo tempo, e os dois caminhos tentam vincular. A solução é o UPDATE
condicional que o codebase já usa em `markAsUsedConditionally`:

```sql
UPDATE subscribers
   SET user_id = :userId, linked_at = now(), link_origin = :origin,
       unlinked_at = NULL, unlinked_by = NULL
 WHERE id = :id
   AND user_id IS NULL
   AND (unlinked_by IS NULL OR unlinked_by <> 'USER')
```

Retorna contagem de linhas: quem obtém 1 venceu, quem obtém 0 não faz nada. A
política inteira fica em SQL — "só vincula se ninguém vinculou antes e se o titular
não recusou". A mesma regra escrita como `if` em Java passaria em teste e falharia
sob concorrência em produção.

`DuplicateKeyException` vinda do índice único é tratada como sucesso idempotente.

### Duas operações distintas

O SQL acima é o do vínculo **automático**. Ele não serve para o titular que
desvinculou e depois quer voltar: a própria cláusula `unlinked_by <> 'USER'` o
bloquearia. São duas operações com regras diferentes:

| Operação | Guarda `unlinked_by <> 'USER'` | Origem gravada |
|---|---|---|
| `autoLink` — verificação de e-mail, confirmação de inscrição, backfill | **sim** | `AUTO_*` |
| `linkOnRequest` — o titular pede pela área da conta | **não** | `MANUAL_USER` |
| `linkByAdmin` — intervenção administrativa | **não**, mas registra em `audit_logs` | `MANUAL_ADMIN` |

A guarda protege o titular de ser re-vinculado sem pedir. Quando é ele mesmo
pedindo, a recusa anterior é exatamente o que está sendo revogado. Ambas mantêm
`user_id IS NULL` na cláusula, então a proteção contra corrida continua valendo
para as três.

## Testes

- **Matriz de estados:** os 4 estados do vínculo × os 3 gatilhos, em teste unitário.
- **Segurança:** usuário não verificado não vincula; quem desvinculou com
  `unlinked_by = 'USER'` não re-vincula **automaticamente**; conta apagada
  re-vincula normalmente.
- **A exceção que confirma a regra:** quem desvinculou *consegue* re-vincular
  quando pede pela área da conta (`linkOnRequest`). Sem este teste, a guarda
  passaria a ser uma porta trancada por fora.
- **Corrida:** dois vínculos concorrentes, exatamente um vence.
- **Backfill idempotente:** rodar a V20 duas vezes num Postgres limpo produz o
  mesmo resultado. Cabe no `PersistencePostgresIntegrationTest` existente.

## Pré-requisitos

A **V18 precisa estar aplicada antes da V20**. `subscribers.email` já é `UNIQUE`,
mas `users.email` só ganha unicidade case-insensitive (`uq_users_email_lower`) na
V18. Sem ela, é teoricamente possível existirem `Ana@x.com` e `ana@x.com` como
contas distintas, e o casamento por e-mail fica ambíguo — o backfill poderia
vincular o inscrito à conta errada.

A V18 está hoje na working tree, ainda não commitada nem implantada. Produção está
na V17.
