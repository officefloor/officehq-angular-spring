-- A single line item can take its own percentage off what it charges. The line's amount is then what it
-- charges less its discount, and the invoice is worked out from those discounted lines. Existing lines
-- have no discount, so no amounts change.
ALTER TABLE invoice_line_item ADD COLUMN discount_pct DECIMAL(5, 2) DEFAULT 0 NOT NULL;

ALTER TABLE invoice_line_item ADD CONSTRAINT invoice_line_item_discount_pct_range
    CHECK (discount_pct >= 0 AND discount_pct <= 100);
