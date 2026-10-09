-- A line item can say what its quantity counts (hours, days, items, ...). Optional: lines raised
-- before units existed, or charged as a single figure, have none.
ALTER TABLE invoice_line_item ADD COLUMN unit VARCHAR(50);
