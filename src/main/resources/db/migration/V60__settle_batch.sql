-- Settle batches: a bulk settle that settles at least one claim (one made PENDING counts) is a
-- batch, numbered 1, 2, ... on its sheet. The sheet keeps the last batch number (0: none yet), and
-- each claim settled by a batch its number (none for a claim settled one at a time).
ALTER TABLE sheet ADD COLUMN last_batch INT NOT NULL DEFAULT 0;
ALTER TABLE claim ADD COLUMN batch INT;
