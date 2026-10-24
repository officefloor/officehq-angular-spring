-- Whether an OPEN or PENDING claim is held: while unbound by a statement it leaves the live totals as
-- a claim of a held person does. Kept through later status changes; false on a claim never held.
ALTER TABLE claim ADD COLUMN held BOOLEAN DEFAULT FALSE NOT NULL;
