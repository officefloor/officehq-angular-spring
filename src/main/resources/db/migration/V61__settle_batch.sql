-- Settle batches: each bulk settle that settles at least one claim (or leaves one pending) is a
-- batch, numbered 1, 2, ... on the sheet; a claim keeps the number of the batch that settled it
-- (null when settled one at a time, or never settled).
ALTER TABLE sheet ADD COLUMN last_settle_batch INTEGER NOT NULL DEFAULT 0;
ALTER TABLE claim ADD COLUMN settle_batch INTEGER;
