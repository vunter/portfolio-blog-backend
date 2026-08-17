-- Verificação de posse do e-mail no cadastro por senha.
-- Até aqui users.email_verified só virava true no fluxo OAuth2 (OAuth2Service),
-- então contas criadas com senha nunca provavam o endereço — qualquer pessoa
-- podia se cadastrar com o e-mail de outra e o sistema tratava como legítimo.
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

-- A busca é sempre por token; o índice único também barra colisão de hash.
CREATE UNIQUE INDEX IF NOT EXISTS uq_email_verification_tokens_token
    ON email_verification_tokens (token);

-- Rate limit por usuário na última hora.
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_user_created
    ON email_verification_tokens (user_id, created_at DESC);

-- A limpeza periódica varre por expiração e por consumo antigo.
CREATE INDEX IF NOT EXISTS idx_email_verification_tokens_cleanup
    ON email_verification_tokens (expires_at, used_at);
