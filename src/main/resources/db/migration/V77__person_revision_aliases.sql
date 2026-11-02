-- A person revision keeps the aliases as text, separated by ";".
ALTER TABLE person_revision ADD COLUMN aliases VARCHAR(4000) DEFAULT '' NOT NULL;
