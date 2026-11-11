-- The number of the bulk settle a claim was settled (or sent to PENDING) by; null for one settled one at a time.
ALTER TABLE claim ADD COLUMN settle_batch BIGINT;
-- The number of the sheet's last bulk settle batch: 0 while there has been none.
ALTER TABLE sheet ADD COLUMN last_settle_batch BIGINT DEFAULT 0 NOT NULL;
