-- Marks a rate's history row that records its withdrawal with no older version to fall back to: from
-- then on the sheet has no rate for the grade from that from-date.
ALTER TABLE rate_history ADD COLUMN withdrawn BOOLEAN DEFAULT FALSE NOT NULL;
