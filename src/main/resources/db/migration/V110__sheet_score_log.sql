-- The score log switch (not dated): while on, changing the sheet's today audits a SCORE line per person
-- before the SNAPSHOT lines. False means blank (off).
ALTER TABLE sheet ADD COLUMN score_log BOOLEAN NOT NULL DEFAULT FALSE;
