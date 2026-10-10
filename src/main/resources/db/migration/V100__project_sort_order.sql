-- Jobs can be put into the order the office wants, and that order is kept. Jobs sort by
-- sort_order, ties broken by id; existing jobs keep the order they were added in.
ALTER TABLE project ADD COLUMN sort_order INT DEFAULT 0 NOT NULL;
UPDATE project SET sort_order = id;
