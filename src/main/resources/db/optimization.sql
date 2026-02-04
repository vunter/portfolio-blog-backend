-- Performance Optimization Indexes
-- These indexes improve query performance for the most common operations

-- Article queries optimization
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_articles_status_published_at 
    ON articles(status, published_at DESC) 
    WHERE status = 'PUBLISHED';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_articles_slug_status 
    ON articles(slug, status);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_articles_author_status 
    ON articles(author_id, status);

-- Full-text search optimization
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_articles_content_gin 
    ON articles USING gin(to_tsvector('english', content));

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_articles_title_gin 
    ON articles USING gin(to_tsvector('english', title));

-- Tag queries optimization
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_article_tags_tag_id 
    ON article_tags(tag_id);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_tags_slug 
    ON tags(slug);

-- Analytics optimization
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_analytics_article_created 
    ON analytics_events(article_id, created_at DESC);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_analytics_event_type 
    ON analytics_events(event_type, created_at DESC);

-- Comments optimization
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_comments_article_status 
    ON comments(article_id, status);

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_comments_parent 
    ON comments(parent_id) 
    WHERE parent_id IS NOT NULL;

-- User queries optimization
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_users_email_lower 
    ON users(LOWER(email));

-- Partial indexes for better performance
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_articles_draft 
    ON articles(created_at DESC) 
    WHERE status = 'DRAFT';

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_articles_archived 
    ON articles(updated_at DESC) 
    WHERE status = 'ARCHIVED';

-- Covering indexes for common queries
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_articles_list_published 
    ON articles(published_at DESC, id, slug, title, excerpt, cover_image_url, views_count, likes_count) 
    WHERE status = 'PUBLISHED';

-- Statistics update
ANALYZE articles;
ANALYZE tags;
ANALYZE article_tags;
ANALYZE comments;
ANALYZE analytics_events;
ANALYZE users;

-- Vacuum for cleanup
VACUUM ANALYZE articles;
VACUUM ANALYZE tags;
