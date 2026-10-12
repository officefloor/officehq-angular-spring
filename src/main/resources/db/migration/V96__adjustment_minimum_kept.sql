-- The part of what an adjustment credit note takes off that it keeps back as a minimum charge, so its invoice (with
-- its earlier notes) is not taken below its minimum charge; 0 for any other invoice.
ALTER TABLE invoice ADD COLUMN minimum_kept DECIMAL(19, 2) DEFAULT 0 NOT NULL;
