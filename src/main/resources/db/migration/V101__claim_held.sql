-- An OPEN or PENDING claim can be held and released again. While held it counts in no live total,
-- as a held person's open claims do, unless a statement binds it. held_on is the sheet's today when
-- it was last held.
ALTER TABLE claim ADD COLUMN held BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN held_on DATE;
