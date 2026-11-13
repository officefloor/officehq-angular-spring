-- Whether a claim is on hold: while held and OPEN or PENDING (and bound by no statement) it counts in no live total.
ALTER TABLE claim ADD COLUMN held BOOLEAN NOT NULL DEFAULT FALSE;
