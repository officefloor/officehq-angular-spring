-- Each person's workflow state: DRAFT, then REVIEW, then FINAL; a new person starts at DRAFT.
ALTER TABLE person ADD COLUMN state VARCHAR(16) DEFAULT 'DRAFT' NOT NULL;
