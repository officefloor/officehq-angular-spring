-- A rate saved with a from-date on or after the sheet's today is forward-only: it applies only to claims added after
-- it was saved. Every claim carries the mark it was added at, from one sequence; a forward-only rate (each version of
-- it, and each change that set it) carries the mark it was saved at, and applies to a claim with a greater mark. A
-- rate with no mark applies to every claim. The claims already on the sheet take mark 0.
CREATE SEQUENCE claim_mark START WITH 1;
ALTER TABLE claim ADD COLUMN added_mark BIGINT DEFAULT 0 NOT NULL;
ALTER TABLE claim ALTER COLUMN added_mark SET DEFAULT NEXT VALUE FOR claim_mark;
ALTER TABLE rate ADD COLUMN after_mark BIGINT;
ALTER TABLE rate_version ADD COLUMN after_mark BIGINT;
ALTER TABLE rate_change ADD COLUMN after_mark BIGINT;
