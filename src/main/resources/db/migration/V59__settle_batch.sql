-- Settle batches: a bulk settle that settles (or sends PENDING) at least one claim is a batch,
-- numbered 1, 2, ... on the sheet in the order the batches happen. Each claim it settled keeps its
-- batch number (null for a claim settled one at a time); the sheet keeps the last it gave out.
ALTER TABLE sheet ADD COLUMN last_settle_batch INT DEFAULT 0 NOT NULL;
ALTER TABLE claim ADD COLUMN settle_batch INT;
