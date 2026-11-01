-- A bulk settle that settles at least one claim (to SETTLED or PENDING) is a batch, numbered 1, 2, ...
-- on the sheet; each claim it settled keeps its batch number (none when settled one at a time).
ALTER TABLE sheet ADD COLUMN last_settle_batch INT NOT NULL DEFAULT 0;
ALTER TABLE claim ADD COLUMN settle_batch INT;
