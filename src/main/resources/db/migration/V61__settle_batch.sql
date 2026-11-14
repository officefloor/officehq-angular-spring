-- Settle batches: each bulk settle that settles at least one claim is a batch, numbered 1, 2, ...
-- on the sheet. The last batch number given on the sheet (0 for none), and the batch that settled
-- each claim (null for a claim not settled by a bulk settle).
ALTER TABLE sheet ADD COLUMN last_batch_number BIGINT NOT NULL DEFAULT 0;
ALTER TABLE claim ADD COLUMN settle_batch BIGINT;
