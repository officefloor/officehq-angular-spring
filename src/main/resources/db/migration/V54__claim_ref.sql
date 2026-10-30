-- A claim's external reference, unique on its sheet without regard to case; null when it has none.
ALTER TABLE claim ADD COLUMN ref VARCHAR(100);
