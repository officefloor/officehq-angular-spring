-- The bulk settle a claim was settled by (sent to PENDING counts): the sheet's batches are
-- numbered 1, 2, ... in the order they happen; none for a claim settled one at a time.
ALTER TABLE claim ADD COLUMN batch INT;
