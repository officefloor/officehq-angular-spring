-- The day the client disputed a sent invoice (null when never): from then it earns no interest, is not
-- overdue and takes no payments until the dispute is resolved.
ALTER TABLE invoice ADD COLUMN disputed_on DATE;
-- The day the dispute was resolved (null while it is open or when there was none): interest runs again from it.
ALTER TABLE invoice ADD COLUMN dispute_resolved_on DATE;
