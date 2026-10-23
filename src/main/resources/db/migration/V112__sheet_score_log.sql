-- Score log switch: while on, changing the sheet's today audits each person's score as it stood on the
-- day being left. Not dated.
ALTER TABLE sheet ADD COLUMN score_log BOOLEAN NOT NULL DEFAULT FALSE;
