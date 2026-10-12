-- The id of the sent invoice this draft was claimed from as its next progress claim; null when it was not.
ALTER TABLE invoice ADD COLUMN claim_from BIGINT;
-- For a line copied into a progress claim: the percentage of its work already billed by the claims before, at most
-- its percent complete; the line bills its percent complete of its full amount less this percentage of it, each
-- rounded half up to the cent. Null when nothing was billed before.
ALTER TABLE invoice_line ADD COLUMN billed_pct DECIMAL(5, 2);
