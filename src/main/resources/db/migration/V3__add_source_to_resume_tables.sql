-- V3: Add source column to all resume child tables for data provenance tracking.
-- Values: 'manual' (default, user-entered) or 'linkedin' (imported via LinkedIn DMA API).
-- Enables selective deletion of LinkedIn-imported data on consent withdrawal (GDPR Art. 17).

ALTER TABLE resume_educations ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
ALTER TABLE resume_experiences ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
ALTER TABLE resume_skills ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
ALTER TABLE resume_languages ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
ALTER TABLE resume_certifications ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
ALTER TABLE resume_additional_info ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
ALTER TABLE resume_home_customization ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
ALTER TABLE resume_testimonials ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
ALTER TABLE resume_proficiencies ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
ALTER TABLE resume_projects ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
ALTER TABLE resume_learning_topics ADD COLUMN IF NOT EXISTS source VARCHAR(20) DEFAULT 'manual';
