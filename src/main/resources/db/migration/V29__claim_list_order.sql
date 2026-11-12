-- A claim's place in its sheet's list, newest first: the next on the sheet when it is added, and again
-- when it is reassigned to another person, so a moved claim is listed as its new person's newest
-- whatever its date. Existing claims keep the order they were added in.
ALTER TABLE claim ADD COLUMN list_order BIGINT;
UPDATE claim SET list_order = id;
ALTER TABLE claim ALTER COLUMN list_order SET NOT NULL;
CREATE INDEX claim_list_order ON claim (sheet_id, list_order);
