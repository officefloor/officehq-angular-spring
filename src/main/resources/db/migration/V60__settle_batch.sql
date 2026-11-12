-- Settle batches: a bulk settle that settles at least one claim (PENDING included) is a batch, numbered
-- 1, 2, ... on its sheet. A claim keeps the number of the batch that settled it; none when settled one at
-- a time, or never settled. The sheet keeps the number of its last batch; 0 before the first.
ALTER TABLE claim ADD COLUMN settle_batch INT;
ALTER TABLE sheet ADD COLUMN last_settle_batch INT NOT NULL DEFAULT 0;
