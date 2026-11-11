-- A claim's external reference: as typed, surrounding spaces removed; null when it has none. A reference is used at
-- most once on a sheet, compared without regard to case.
ALTER TABLE claim ADD COLUMN ref VARCHAR(255);
