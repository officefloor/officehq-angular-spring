-- The number of the last bulk settle that settled a claim on the sheet; 0 before any.
ALTER TABLE sheet ADD COLUMN last_settle_batch INT DEFAULT 0 NOT NULL;
-- The bulk settle batch that settled the claim (to SETTLED or PENDING); null when settled one at a time or never.
ALTER TABLE claim ADD COLUMN settle_batch INT;
