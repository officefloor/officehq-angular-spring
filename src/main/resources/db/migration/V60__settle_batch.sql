-- A bulk settle that settles at least one claim (one made PENDING counts) is a batch, numbered on its sheet in the
-- order the batches happen: the sheet keeps the number of its last batch (0 for none), and each claim settled by one
-- keeps its number (null for a claim settled one at a time, or not settled).
ALTER TABLE sheet ADD COLUMN last_settle_batch INT NOT NULL DEFAULT 0;
ALTER TABLE claim ADD COLUMN settle_batch INT;
