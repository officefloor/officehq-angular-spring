-- A sent invoice keeps a snapshot of how it read when it was sent (its total and the day it went out), so it
-- can still be viewed as sent after later rule changes alter its live figures. Unset until it is sent.
ALTER TABLE invoice ADD COLUMN sent_snapshot_total DECIMAL(12, 2);
ALTER TABLE invoice ADD COLUMN sent_snapshot_date DATE;

-- An invoice already sent is taken as it reads now, sent on the day it was issued.
UPDATE invoice SET sent_snapshot_total = amount, sent_snapshot_date = issued_date
WHERE status <> 'DRAFT';
