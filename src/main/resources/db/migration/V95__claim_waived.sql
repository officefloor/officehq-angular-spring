-- A steward can waive an OPEN or PENDING claim's late charge: from then on it bears none.
ALTER TABLE claim ADD COLUMN waived BOOLEAN DEFAULT FALSE NOT NULL;
