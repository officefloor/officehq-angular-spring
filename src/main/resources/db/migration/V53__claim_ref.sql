-- The claim's external reference, unique on its sheet without regard to case; none when not given.
ALTER TABLE claim ADD COLUMN ref VARCHAR(120);
