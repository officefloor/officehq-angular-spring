-- A rate withdrawn with no older version gets a last revision marked gone, stamped with the sheet's
-- today then: the sheet as it stood at the end of a later day no longer has it.
ALTER TABLE rate_revision ADD COLUMN gone BOOLEAN DEFAULT FALSE NOT NULL;
