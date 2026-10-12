-- The day a disputed line was taken off its sent invoice by a credit note, settling it in the client's favour: from
-- then it is out of the dispute (it held back interest until then). Null while the line is still in dispute.
ALTER TABLE invoice_line ADD COLUMN dispute_settled_on DATE;
