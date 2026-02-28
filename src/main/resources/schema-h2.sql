-- ============================================
-- Portfolio Blog - Database Schema (H2 Compatible)
-- Uses BIGINT for IDs (Snowflake ID format)
-- ============================================

-- Users table (admin authentication)
CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    username VARCHAR(100) UNIQUE,
    avatar_url VARCHAR(500),
    bio TEXT,
    role VARCHAR(50) DEFAULT 'VIEWER',
    active BOOLEAN DEFAULT TRUE,
    email_verified BOOLEAN DEFAULT FALSE,
    cf_email_rule_id VARCHAR(100),
    mfa_enabled BOOLEAN DEFAULT FALSE,
    mfa_preferred_method VARCHAR(20) DEFAULT 'TOTP',
    account_locked_until TIMESTAMP,
    failed_login_attempts INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Articles table
CREATE TABLE IF NOT EXISTS articles (
    id BIGINT PRIMARY KEY,
    slug VARCHAR(255) UNIQUE NOT NULL,
    title VARCHAR(500) NOT NULL,
    subtitle VARCHAR(500),
    content TEXT NOT NULL,
    excerpt TEXT,
    cover_image_url VARCHAR(500),
    author_id BIGINT REFERENCES users(id),
    status VARCHAR(50) DEFAULT 'DRAFT',
    published_at TIMESTAMP,
    scheduled_at TIMESTAMP,
    reading_time_minutes INTEGER,
    views_count INTEGER DEFAULT 0,
    likes_count INTEGER DEFAULT 0,
    seo_title VARCHAR(255),
    seo_description VARCHAR(500),
    seo_keywords VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Tags table
CREATE TABLE IF NOT EXISTS tags (
    id BIGINT PRIMARY KEY,
    name VARCHAR(100) UNIQUE NOT NULL,
    slug VARCHAR(100) UNIQUE NOT NULL,
    description TEXT,
    color VARCHAR(7),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Article Tags (Many-to-Many)
CREATE TABLE IF NOT EXISTS article_tags (
    article_id BIGINT REFERENCES articles(id) ON DELETE CASCADE,
    tag_id BIGINT REFERENCES tags(id) ON DELETE CASCADE,
    PRIMARY KEY (article_id, tag_id)
);

-- Comments table
CREATE TABLE IF NOT EXISTS comments (
    id BIGINT PRIMARY KEY,
    article_id BIGINT REFERENCES articles(id) ON DELETE CASCADE,
    author_name VARCHAR(255) NOT NULL,
    author_email VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    parent_id BIGINT REFERENCES comments(id),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Analytics Events table
CREATE TABLE IF NOT EXISTS analytics_events (
    id BIGINT PRIMARY KEY,
    article_id BIGINT REFERENCES articles(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    user_ip VARCHAR(45),
    user_agent TEXT,
    referrer VARCHAR(500),
    metadata TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for performance
CREATE INDEX IF NOT EXISTS idx_articles_slug ON articles(slug);
CREATE INDEX IF NOT EXISTS idx_articles_status ON articles(status);
CREATE INDEX IF NOT EXISTS idx_articles_published_at ON articles(published_at DESC);
CREATE INDEX IF NOT EXISTS idx_articles_scheduled_at ON articles(scheduled_at);
CREATE INDEX IF NOT EXISTS idx_articles_author_id ON articles(author_id);
CREATE INDEX IF NOT EXISTS idx_articles_views ON articles(views_count DESC);
CREATE INDEX IF NOT EXISTS idx_articles_likes ON articles(likes_count DESC);
CREATE INDEX IF NOT EXISTS idx_comments_article_id ON comments(article_id);
CREATE INDEX IF NOT EXISTS idx_comments_author_email ON comments(author_email);
CREATE INDEX IF NOT EXISTS idx_comments_status ON comments(status);
CREATE INDEX IF NOT EXISTS idx_comments_parent_id ON comments(parent_id);
CREATE INDEX IF NOT EXISTS idx_analytics_article_id ON analytics_events(article_id);
CREATE INDEX IF NOT EXISTS idx_analytics_created_at ON analytics_events(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_event_type ON analytics_events(event_type);
CREATE INDEX IF NOT EXISTS idx_analytics_composite ON analytics_events(article_id, event_type, created_at DESC);

-- Refresh Tokens table
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id BIGINT PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) UNIQUE NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    revoked BOOLEAN DEFAULT FALSE
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_token ON refresh_tokens(token);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires ON refresh_tokens(expires_at);

-- Subscribers table (Newsletter)
CREATE TABLE IF NOT EXISTS subscribers (
    id BIGINT PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    name VARCHAR(255),
    status VARCHAR(50) DEFAULT 'PENDING',
    confirmation_token VARCHAR(255),
    unsubscribe_token VARCHAR(255),
    confirmed_at TIMESTAMP,
    unsubscribed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_subscribers_email ON subscribers(email);
CREATE INDEX IF NOT EXISTS idx_subscribers_status ON subscribers(status);
CREATE INDEX IF NOT EXISTS idx_subscribers_confirmation_token ON subscribers(confirmation_token);
CREATE INDEX IF NOT EXISTS idx_subscribers_unsubscribe_token ON subscribers(unsubscribe_token);

-- Article Versions table (for version history)
CREATE TABLE IF NOT EXISTS article_versions (
    id BIGINT PRIMARY KEY,
    article_id BIGINT REFERENCES articles(id) ON DELETE CASCADE,
    version_number INTEGER NOT NULL,
    title VARCHAR(500) NOT NULL,
    subtitle VARCHAR(500),
    content TEXT NOT NULL,
    excerpt TEXT,
    cover_image_url VARCHAR(500),
    change_summary VARCHAR(500),
    changed_by BIGINT REFERENCES users(id),
    changed_by_name VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(article_id, version_number)
);

CREATE INDEX IF NOT EXISTS idx_article_versions_article_id ON article_versions(article_id);
CREATE INDEX IF NOT EXISTS idx_article_versions_version ON article_versions(article_id, version_number DESC);

-- Audit Logs table (for tracking admin actions)
CREATE TABLE IF NOT EXISTS audit_logs (
    id BIGINT PRIMARY KEY,
    action VARCHAR(100) NOT NULL,
    entity_type VARCHAR(100) NOT NULL,
    entity_id VARCHAR(255),
    performed_by BIGINT REFERENCES users(id) ON DELETE SET NULL,
    performed_by_email VARCHAR(255),
    details TEXT,
    ip_address VARCHAR(45),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_action ON audit_logs(action);
CREATE INDEX IF NOT EXISTS idx_audit_logs_entity ON audit_logs(entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_logs_performed_by ON audit_logs(performed_by);
CREATE INDEX IF NOT EXISTS idx_audit_logs_created_at ON audit_logs(created_at DESC);

-- Password Reset Tokens table
CREATE TABLE IF NOT EXISTS password_reset_tokens (
    id BIGINT PRIMARY KEY,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    token VARCHAR(255) UNIQUE NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    used BOOLEAN DEFAULT FALSE,
    used_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_password_reset_token ON password_reset_tokens(token);
CREATE INDEX IF NOT EXISTS idx_password_reset_user_id ON password_reset_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_password_reset_expires ON password_reset_tokens(expires_at);

-- Resume Templates table (for HTML resume templates and PDF generation)
CREATE TABLE IF NOT EXISTS resume_templates (
    id BIGINT PRIMARY KEY,
    slug VARCHAR(255) UNIQUE NOT NULL,
    url_alias VARCHAR(255) UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    html_content TEXT NOT NULL,
    css_content TEXT,
    status VARCHAR(50) DEFAULT 'DRAFT',
    owner_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    version INTEGER DEFAULT 1,
    is_default BOOLEAN DEFAULT FALSE,
    paper_size VARCHAR(20) DEFAULT 'A4',
    orientation VARCHAR(20) DEFAULT 'PORTRAIT',
    download_count INTEGER DEFAULT 0,
    preview_url VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_templates_slug ON resume_templates(slug);
CREATE INDEX IF NOT EXISTS idx_resume_templates_alias ON resume_templates(url_alias);
CREATE INDEX IF NOT EXISTS idx_resume_templates_owner_id ON resume_templates(owner_id);
CREATE INDEX IF NOT EXISTS idx_resume_templates_status ON resume_templates(status);
CREATE INDEX IF NOT EXISTS idx_resume_templates_owner_status ON resume_templates(owner_id, status);
CREATE INDEX IF NOT EXISTS idx_resume_templates_default ON resume_templates(owner_id);
CREATE INDEX IF NOT EXISTS idx_resume_templates_downloads ON resume_templates(download_count DESC);
CREATE INDEX IF NOT EXISTS idx_resume_templates_updated ON resume_templates(updated_at DESC);

-- Resume Profile tables (for structured resume data)
CREATE TABLE IF NOT EXISTS resume_profiles (
    id BIGINT PRIMARY KEY,
    owner_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    locale VARCHAR(10) DEFAULT 'en' NOT NULL,
    full_name VARCHAR(255) NOT NULL,
    title VARCHAR(255),
    email VARCHAR(255),
    phone VARCHAR(50),
    linkedin VARCHAR(255),
    github VARCHAR(255),
    website VARCHAR(255),
    location VARCHAR(255),
    professional_summary TEXT,
    interests TEXT,
    work_mode TEXT,
    timezone TEXT,
    employment_type TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_resume_profiles_owner_locale ON resume_profiles(owner_id, locale);

CREATE TABLE IF NOT EXISTS resume_educations (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    institution VARCHAR(255),
    location VARCHAR(255),
    degree VARCHAR(255),
    field_of_study VARCHAR(255),
    start_date VARCHAR(50),
    end_date VARCHAR(50),
    description TEXT,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_educations_profile ON resume_educations(profile_id);

CREATE TABLE IF NOT EXISTS resume_experiences (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    company VARCHAR(255),
    position VARCHAR(255),
    start_date VARCHAR(50),
    end_date VARCHAR(50),
    bullets TEXT,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_experiences_profile ON resume_experiences(profile_id);

CREATE TABLE IF NOT EXISTS resume_skills (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    category VARCHAR(255),
    content TEXT,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_skills_profile ON resume_skills(profile_id);

CREATE TABLE IF NOT EXISTS resume_languages (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    name VARCHAR(100),
    proficiency VARCHAR(100),
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_languages_profile ON resume_languages(profile_id);

CREATE TABLE IF NOT EXISTS resume_certifications (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    name VARCHAR(255),
    issuer VARCHAR(255),
    issue_date VARCHAR(50),
    credential_url VARCHAR(500),
    description TEXT,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_certifications_profile ON resume_certifications(profile_id);

CREATE TABLE IF NOT EXISTS resume_additional_info (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    label VARCHAR(255),
    content TEXT,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_additional_info_profile ON resume_additional_info(profile_id);

-- Home Page Customization (key-value settings for the public home page, separate from resume content)
CREATE TABLE IF NOT EXISTS resume_home_customization (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    label VARCHAR(255),
    content TEXT,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_home_customization_profile ON resume_home_customization(profile_id);

-- Resume Testimonials (home page recommendations)
CREATE TABLE IF NOT EXISTS resume_testimonials (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    author_name VARCHAR(255),
    author_role VARCHAR(255),
    author_company VARCHAR(255),
    author_image_url VARCHAR(500),
    text TEXT,
    accent_color VARCHAR(50),
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_testimonials_profile ON resume_testimonials(profile_id);

-- Resume Proficiencies (skill bars on home page)
CREATE TABLE IF NOT EXISTS resume_proficiencies (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    category VARCHAR(255),
    skill_name VARCHAR(255),
    percentage INTEGER,
    icon VARCHAR(255),
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_proficiencies_profile ON resume_proficiencies(profile_id);

-- Resume Projects (portfolio projects on home page)
CREATE TABLE IF NOT EXISTS resume_projects (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    title VARCHAR(255),
    description TEXT,
    image_url VARCHAR(500),
    project_url VARCHAR(500),
    repo_url VARCHAR(500),
    tech_tags TEXT,
    featured BOOLEAN DEFAULT FALSE,
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_projects_profile ON resume_projects(profile_id);

-- Resume Learning Topics ("Always Learning" cards on home page)
CREATE TABLE IF NOT EXISTS resume_learning_topics (
    id BIGINT PRIMARY KEY,
    profile_id BIGINT REFERENCES resume_profiles(id) ON DELETE CASCADE,
    title VARCHAR(255),
    emoji VARCHAR(500),
    description TEXT,
    color_theme VARCHAR(100),
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_resume_learning_topics_profile ON resume_learning_topics(profile_id);

-- Contacts table (Contact form submissions)
CREATE TABLE IF NOT EXISTS contacts (
    id BIGINT PRIMARY KEY,
    public_id VARCHAR(36) UNIQUE NOT NULL,
    name VARCHAR(100) NOT NULL,
    email VARCHAR(255) NOT NULL,
    subject VARCHAR(200) NOT NULL,
    message TEXT NOT NULL,
    is_read BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_contacts_public_id ON contacts(public_id);
CREATE INDEX IF NOT EXISTS idx_contacts_created_at ON contacts(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_contacts_unread ON contacts(is_read, created_at DESC);

-- Article i18n translations table (Translation Table Wide approach)
ALTER TABLE articles ADD COLUMN IF NOT EXISTS original_locale VARCHAR(10) DEFAULT 'en';

-- Supported languages (centralised source of truth)
CREATE TABLE IF NOT EXISTS languages (
    code VARCHAR(10) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    native_name VARCHAR(100),
    is_default BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    fallback_code VARCHAR(10),
    sort_order INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

MERGE INTO languages (code, name, native_name, is_default, is_active, fallback_code, sort_order) KEY(code) VALUES
  ('en',    'English',              'English',    TRUE,  TRUE, NULL,  0);
MERGE INTO languages (code, name, native_name, is_default, is_active, fallback_code, sort_order) KEY(code) VALUES
  ('pt-br', 'Brazilian Portuguese', 'Português',  FALSE, TRUE, 'en',  1);
MERGE INTO languages (code, name, native_name, is_default, is_active, fallback_code, sort_order) KEY(code) VALUES
  ('es',    'Spanish',              'Español',    FALSE, FALSE, 'en', 2);
MERGE INTO languages (code, name, native_name, is_default, is_active, fallback_code, sort_order) KEY(code) VALUES
  ('it',    'Italian',              'Italiano',   FALSE, FALSE, 'en', 3);

CREATE TABLE IF NOT EXISTS article_i18n (
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    locale VARCHAR(10) NOT NULL,
    title VARCHAR(500) NOT NULL,
    subtitle VARCHAR(500),
    content TEXT NOT NULL,
    excerpt TEXT,
    seo_title VARCHAR(255),
    seo_description VARCHAR(500),
    seo_keywords VARCHAR(500),
    auto_translated BOOLEAN DEFAULT FALSE,
    reviewed BOOLEAN DEFAULT FALSE,
    translated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (article_id, locale)
);

CREATE INDEX IF NOT EXISTS idx_article_i18n_locale ON article_i18n(locale);
CREATE INDEX IF NOT EXISTS idx_article_i18n_article ON article_i18n(article_id);

-- ============================================
-- Site Settings table (FEAT-01)
-- ============================================
CREATE TABLE IF NOT EXISTS site_settings (
    id BIGINT PRIMARY KEY,
    setting_key VARCHAR(255) UNIQUE NOT NULL,
    setting_value TEXT,
    setting_type VARCHAR(50) DEFAULT 'STRING',
    description TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_site_settings_key ON site_settings(setting_key);

-- Bookmarks table (anonymous visitor bookmarks)
CREATE TABLE IF NOT EXISTS bookmarks (
    id BIGINT PRIMARY KEY,
    article_id BIGINT NOT NULL REFERENCES articles(id) ON DELETE CASCADE,
    user_id BIGINT REFERENCES users(id) ON DELETE CASCADE,
    visitor_hash VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_bookmark_visitor UNIQUE (article_id, visitor_hash),
    CONSTRAINT uq_bookmark_user UNIQUE (article_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_bookmarks_visitor ON bookmarks(visitor_hash);
CREATE INDEX IF NOT EXISTS idx_bookmarks_user ON bookmarks(user_id);
CREATE INDEX IF NOT EXISTS idx_bookmarks_article ON bookmarks(article_id);
CREATE INDEX IF NOT EXISTS idx_bookmarks_created_at ON bookmarks(created_at DESC);

-- Migration: Add updated_at to comments
ALTER TABLE comments ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- ============================================
-- Role Upgrade Requests
-- ============================================
CREATE TABLE IF NOT EXISTS role_upgrade_requests (
    id BIGINT PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    requested_role VARCHAR(50) NOT NULL,
    reason TEXT,
    status VARCHAR(50) DEFAULT 'PENDING',
    reviewed_by BIGINT REFERENCES users(id),
    reviewed_at TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_role_upgrade_requests_user ON role_upgrade_requests(user_id);
CREATE INDEX IF NOT EXISTS idx_role_upgrade_requests_status ON role_upgrade_requests(status);

-- ============================================
-- Media Assets (reusable photo/file storage)
-- ============================================
CREATE TABLE IF NOT EXISTS media_assets (
    id                BIGINT PRIMARY KEY,
    original_filename VARCHAR(500)  NOT NULL,
    stored_filename   VARCHAR(500)  NOT NULL,
    storage_key       VARCHAR(1000) NOT NULL,
    url               VARCHAR(1000) NOT NULL,
    content_type      VARCHAR(100)  NOT NULL,
    file_size         BIGINT        NOT NULL,
    purpose           VARCHAR(50)   NOT NULL DEFAULT 'GENERAL',
    alt_text          VARCHAR(500),
    uploader_id       BIGINT REFERENCES users(id) ON DELETE SET NULL,
    created_at        TIMESTAMP     DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_media_assets_purpose     ON media_assets(purpose);
CREATE INDEX IF NOT EXISTS idx_media_assets_uploader    ON media_assets(uploader_id);
CREATE INDEX IF NOT EXISTS idx_media_assets_created     ON media_assets(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_media_assets_storage_key ON media_assets(storage_key);

-- ============================================
-- MFA / Two-Factor Authentication
-- ============================================

-- User MFA configuration (TOTP secrets, backup codes)
CREATE TABLE IF NOT EXISTS user_mfa_config (
    id              BIGINT PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    method          VARCHAR(20) NOT NULL DEFAULT 'TOTP',
    secret_encrypted VARCHAR(512),
    verified        BOOLEAN DEFAULT FALSE,
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE(user_id, method)
);

CREATE INDEX IF NOT EXISTS idx_user_mfa_config_user ON user_mfa_config(user_id);
CREATE INDEX IF NOT EXISTS idx_user_mfa_config_method ON user_mfa_config(user_id, method);
