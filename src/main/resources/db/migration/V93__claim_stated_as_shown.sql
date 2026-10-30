-- A statement now binds a claim's figure as shown, late charge and floor included (stated_due), so it
-- no longer follows the sheet's today while bound. A claim bound before this (stated_as_shown false)
-- keeps its bound figure without its late charge, as it was bound.
ALTER TABLE claim ADD COLUMN stated_as_shown BOOLEAN DEFAULT FALSE NOT NULL;
