-- Apply once to an existing PostgreSQL database before deploying the updated server.
-- Existing configurations retain their OpenAI-compatible behavior.
-- Run with psql --single-transaction; startup does not execute this migration.
ALTER TABLE user_ai_config
    ADD COLUMN api_format varchar(16) NOT NULL DEFAULT 'OPENAI'
        CHECK (api_format IN ('OPENAI', 'ANTHROPIC')),
    ADD COLUMN max_tokens integer NOT NULL DEFAULT 1024
        CHECK (max_tokens BETWEEN 1 AND 128000);
COMMENT ON COLUMN user_ai_config.api_format IS 'Chat API wire format: OPENAI or ANTHROPIC';
COMMENT ON COLUMN user_ai_config.max_tokens IS 'Maximum output tokens for Anthropic Messages';
