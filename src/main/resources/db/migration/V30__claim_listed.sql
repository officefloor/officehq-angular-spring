-- A claim's place in its sheet's list, newest first: the claims are listed by it, highest first. A
-- claim is given the next value of its sheet's listed sequence when it is added, and again when it is
-- moved to another person, so a moved claim is listed as its new person's newest whatever its date.
ALTER TABLE claim ADD COLUMN listed BIGINT;
UPDATE claim SET listed = id;
ALTER TABLE claim ALTER COLUMN listed SET NOT NULL;

-- Start each sheet's listed sequence above the claims it already has.
INSERT INTO claim_sequence (sheet_id, seq_key, last_number)
    SELECT sheet_id, 'listed', MAX(id) FROM claim GROUP BY sheet_id;
