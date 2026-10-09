-- Sales tax is now charged on the taxable lines only: tax-free lines are left out of what the tax is
-- worked out on. Rework the stored amount of draft invoices to match (subtotal less the discount, plus
-- the tax on the taxable lines less their discount). Invoices already sent keep the amount they were
-- issued for.
ALTER TABLE invoice ADD COLUMN rework_subtotal DECIMAL(14, 2);
ALTER TABLE invoice ADD COLUMN rework_taxable DECIMAL(14, 2);

UPDATE invoice i SET
    rework_subtotal = (SELECT COALESCE(SUM(ROUND(l.qty * l.unit_price, 2)), 0)
                       FROM invoice_line_item l WHERE l.invoice_id = i.id),
    rework_taxable = (SELECT COALESCE(SUM(ROUND(l.qty * l.unit_price, 2)), 0)
                      FROM invoice_line_item l WHERE l.invoice_id = i.id AND NOT l.tax_exempt)
WHERE i.status = 'DRAFT'
  AND EXISTS (SELECT 1 FROM invoice_line_item l WHERE l.invoice_id = i.id AND l.tax_exempt);

UPDATE invoice SET amount = rework_subtotal - ROUND(rework_subtotal * discount_pct / 100, 2)
        + ROUND((rework_taxable - ROUND(rework_taxable * discount_pct / 100, 2)) * tax_pct / 100, 2)
WHERE rework_subtotal IS NOT NULL;

ALTER TABLE invoice DROP COLUMN rework_subtotal;
ALTER TABLE invoice DROP COLUMN rework_taxable;
