-- V10: Add foreign key constraints for locale columns referencing languages.code.
-- Ensures referential integrity: all locale values must exist in the languages table.

-- article_i18n.locale → languages.code
DO $$ BEGIN
    ALTER TABLE article_i18n
        ADD CONSTRAINT fk_article_i18n_locale
        FOREIGN KEY (locale) REFERENCES languages(code) ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ui_translations.locale → languages.code
DO $$ BEGIN
    -- Clean up any orphan locale codes before adding FK
    DELETE FROM ui_translations
    WHERE locale NOT IN (SELECT code FROM languages);

    ALTER TABLE ui_translations
        ADD CONSTRAINT fk_ui_translations_locale
        FOREIGN KEY (locale) REFERENCES languages(code) ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- resume_profiles.locale → languages.code
DO $$ BEGIN
    ALTER TABLE resume_profiles
        ADD CONSTRAINT fk_resume_profiles_locale
        FOREIGN KEY (locale) REFERENCES languages(code) ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- articles.original_locale → languages.code
DO $$ BEGIN
    -- Ensure all articles have a valid locale (default to 'en' if null)
    UPDATE articles SET original_locale = 'en' WHERE original_locale IS NULL;

    ALTER TABLE articles
        ADD CONSTRAINT fk_articles_original_locale
        FOREIGN KEY (original_locale) REFERENCES languages(code) ON UPDATE CASCADE;
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
