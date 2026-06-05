# Audit Fixes Design

**Goal:** Corrigir os problemas de backend e frontend auditados, usando os repos separados `portfolio-blog-backend` e `portfolio-blog-frontend` como fonte de verdade.

**Scope & Repos:**
- Aplicar mudanças apenas nos repos separados (mantendo commits locais existentes).
- Atualizar o monorepo `portfolio-blog` via submodules **apenas se solicitado**.

**Non-goals:**
- Refatorações amplas não relacionadas aos issues auditados.
- Mudanças em infraestrutura fora dos repos separados.

## Backend (portfolio-blog-backend)
1. **Jobs `@Scheduled` reativos**: transformar em métodos `void` e chamar `service.method().subscribe(...)` com logging explícito de erro para evitar execução silenciosa.
2. **JWT inválido em rotas públicas**: tratar como usuário anônimo; limpar cookies e permitir request em endpoints `permitAll`.
3. **Logout com múltiplos cookies**: revogar todos os refresh tokens presentes nos cookies (ou primeiro válido encontrado no banco) para evitar sessão ativa.
4. **Export limit (500)**: substituir `IllegalStateException` por exceção mapeada a 4xx (`ResponseStatusException` ou custom + handler).
5. **Upload de mídia**: validar `contentLength` e/ou limitar stream antes de bufferizar, evitando consumo de memória.
6. **Unsubscribe por email**: mudar para fluxo de confirmação (gera token e envia email; somente confirma por token).

## Frontend (portfolio-blog-frontend)
1. **Consentimento funcional**: limpar `bookmarked-articles`/`visitor-id` e bloquear leitura/escrita sem consentimento.
2. **Acessibilidade**: tornar elementos clicáveis focáveis e operáveis por teclado (toast, cards, multi-select, botões/links).
3. **ToC**: gerar IDs únicos para headings duplicados.
4. **Share fallback**: sucesso/erro real conforme resultado do clipboard.
5. **Labels de share**: mover para i18n.
6. **Diálogos**: `aria-modal`, foco inicial e `Esc` para fechar (menu mobile e cookie banner).

## Error Handling
- Sem `try/catch` genérico.
- Logs explícitos em fluxos assíncronos (subscribe).
- Retornos 4xx previsíveis em erros de validação/limites.

## Testes
- Backend: `./mvnw test`
- Frontend: `npm test` (ou `ng test`) e `ng build`
- Validação manual: consentimento, share fallback, navegação por teclado.

## Rollout
- Se necessário, atualizar submodules no monorepo `portfolio-blog` após as correções.
