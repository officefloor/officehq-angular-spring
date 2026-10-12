-- The day the client disputed a reached milestone; null when it is not in dispute. While it is, the milestone
-- earns no interest, is not overdue and takes no payments; resolving the dispute clears it.
ALTER TABLE invoice_milestone ADD COLUMN disputed_on DATE;
