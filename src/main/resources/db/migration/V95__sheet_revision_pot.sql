-- A sheet's pot is kept in its history with its policy date, so the pools' cuts can be worked out
-- as of an earlier day. The pot a sheet holds already is a revision, stamped with its today.
ALTER TABLE sheet_revision ADD COLUMN pot DECIMAL(14, 2);
INSERT INTO sheet_revision (sheet_id, recorded_on, policy_date, pot)
    SELECT id, today, policy_date, pot FROM sheet WHERE pot IS NOT NULL;
