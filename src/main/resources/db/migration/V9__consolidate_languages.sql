-- V9: Consolidate language tables and standardize locale codes.
-- Q6.1: Normalize locale to lowercase BCP-47 (pt-br not pt-BR or pt).
-- Q13.6: Merge supported_languages into languages and drop the duplicate table.

-- ============================================
-- Step 0: Add locale column to email_custom_variables if missing
-- (schema.sql defines it but older databases may not have it)
-- ============================================
ALTER TABLE email_custom_variables ADD COLUMN IF NOT EXISTS locale VARCHAR(10) NOT NULL DEFAULT '*';

-- Update unique constraint to include locale
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'email_custom_variables_var_key_template_id_key') THEN
        ALTER TABLE email_custom_variables DROP CONSTRAINT email_custom_variables_var_key_template_id_key;
    END IF;
    ALTER TABLE email_custom_variables ADD CONSTRAINT email_custom_variables_var_key_template_id_locale_key
        UNIQUE (var_key, template_id, locale);
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ============================================
-- Step 1: Standardize locale values across the codebase
-- ============================================

-- Ensure 'pt-br' exists in languages (it should from V1, but be safe)
INSERT INTO languages (code, name, native_name, is_default, is_active, fallback_code, sort_order)
VALUES ('pt-br', 'Brazilian Portuguese', 'Português', FALSE, TRUE, 'en', 1)
ON CONFLICT (code) DO NOTHING;

-- Update any rows using 'pt-BR' (case mismatch) in referencing tables
UPDATE article_i18n SET locale = 'pt-br' WHERE locale = 'pt-BR';
UPDATE article_i18n SET locale = 'pt-br' WHERE locale = 'pt';

UPDATE ui_translations SET locale = 'pt-br' WHERE locale = 'pt-BR';
UPDATE ui_translations SET locale = 'pt-br' WHERE locale = 'pt';

UPDATE resume_profiles SET locale = 'pt-br' WHERE locale = 'pt-BR';
UPDATE resume_profiles SET locale = 'pt-br' WHERE locale = 'pt';

-- Also normalise articles.original_locale
UPDATE articles SET original_locale = 'pt-br' WHERE original_locale IN ('pt-BR', 'pt');

-- Normalise users.preferred_locale
UPDATE users SET preferred_locale = 'pt-br' WHERE preferred_locale IN ('pt-BR', 'pt');

-- Normalise email_custom_variables.locale
UPDATE email_custom_variables SET locale = 'pt-br' WHERE locale IN ('pt-BR', 'pt');

-- ============================================
-- Step 2: Migrate any supported_languages rows missing from languages
-- ============================================

-- supported_languages has: code, name, native_name, enabled, sort_order
-- languages has: code, name, native_name, is_default, is_active, fallback_code, sort_order
-- Map enabled -> is_active, default is_default to FALSE, fallback_code to 'en'

INSERT INTO languages (code, name, native_name, is_default, is_active, fallback_code, sort_order)
SELECT
    CASE WHEN sl.code = 'pt' THEN 'pt-br' ELSE sl.code END,
    sl.name,
    sl.native_name,
    FALSE,
    sl.enabled,
    'en',
    sl.sort_order
FROM supported_languages sl
WHERE NOT EXISTS (
    SELECT 1 FROM languages l
    WHERE l.code = CASE WHEN sl.code = 'pt' THEN 'pt-br' ELSE sl.code END
);

-- ============================================
-- Step 3: Drop the redundant supported_languages table
-- ============================================
DROP TABLE IF EXISTS supported_languages;
