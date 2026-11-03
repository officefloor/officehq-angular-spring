-- A person or claim that leaves the sheet (transferred or merged away) gets a last revision marked
-- gone, stamped with the sheet's today then: the sheet as it stood at the end of a later day no
-- longer has them.
ALTER TABLE person_revision ADD COLUMN gone BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim_revision ADD COLUMN gone BOOLEAN DEFAULT FALSE NOT NULL;
