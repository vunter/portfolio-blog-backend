-- V11: Add CHECK constraints on status/enum columns not yet covered.
-- V1 already has: chk_users_role, chk_articles_status, chk_comments_status.

-- subscribers.status
DO $$ BEGIN
    ALTER TABLE subscribers
        ADD CONSTRAINT chk_subscribers_status
        CHECK (status IN ('PENDING', 'CONFIRMED', 'UNSUBSCRIBED'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- newsletter_events.event_type
DO $$ BEGIN
    ALTER TABLE newsletter_events
        ADD CONSTRAINT chk_newsletter_events_type
        CHECK (event_type IN ('SENT', 'OPEN', 'CLICK', 'DELIVERED', 'BOUNCED', 'FAILED'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- article_reviews.status
DO $$ BEGIN
    ALTER TABLE article_reviews
        ADD CONSTRAINT chk_article_reviews_status
        CHECK (status IN ('APPROVED', 'CHANGES_REQUESTED'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- role_upgrade_requests.status
DO $$ BEGIN
    ALTER TABLE role_upgrade_requests
        ADD CONSTRAINT chk_role_upgrade_requests_status
        CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- resume_templates.status
DO $$ BEGIN
    ALTER TABLE resume_templates
        ADD CONSTRAINT chk_resume_templates_status
        CHECK (status IN ('DRAFT', 'ACTIVE', 'ARCHIVED'));
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

-- ============================================
-- Email format validation (basic RFC check)
-- ============================================

DO $$ BEGIN
    ALTER TABLE users
        ADD CONSTRAINT chk_users_email_format
        CHECK (email ~* '^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE subscribers
        ADD CONSTRAINT chk_subscribers_email_format
        CHECK (email ~* '^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE contacts
        ADD CONSTRAINT chk_contacts_email_format
        CHECK (email ~* '^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

DO $$ BEGIN
    ALTER TABLE comments
        ADD CONSTRAINT chk_comments_email_format
        CHECK (author_email ~* '^[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}$');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;
