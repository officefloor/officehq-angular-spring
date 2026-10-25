-- Settle batches: a bulk settle that settles (or leaves pending) at least one claim is the sheet's
-- next batch, numbered 1, 2, ... Each claim it settled keeps the batch's number; null for a claim
-- settled one at a time. The sheet counts the batches it has had.
ALTER TABLE sheet ADD COLUMN settle_batches_used INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN settle_batch INT;
