-- A bulk settle that settles (or leaves PENDING) at least one claim is a batch, numbered 1, 2, ... on
-- its sheet. A claim keeps the number of the batch that settled it (null when settled one at a time,
-- or not settled); the sheet keeps the number of its last batch (null while it has had none).
ALTER TABLE claim ADD COLUMN settle_batch INTEGER;
ALTER TABLE sheet ADD COLUMN last_settle_batch INTEGER;
