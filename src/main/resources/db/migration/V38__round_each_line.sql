-- Each line is worked out and rounded to the cent first, then the lines are added up, so a unit price
-- can now be given to a fraction of a cent (up to four decimal places): two lines of 10.005 are 10.01
-- each and 20.02 together. The sales tax and levy are likewise worked out and rounded on each taxable
-- line (after its discount) and then added up. Rework the stored amount of draft invoices that add tax
-- on top to match; tax-inclusive drafts owe the discounted subtotal, which is unchanged, and invoices
-- already sent keep the amount they were issued for.
ALTER TABLE invoice_line_item ALTER COLUMN unit_price DECIMAL(14, 4);

ALTER TABLE invoice ADD COLUMN rework_subtotal DECIMAL(14, 2);
ALTER TABLE invoice ADD COLUMN rework_taxes DECIMAL(14, 2);

UPDATE invoice i SET
    rework_subtotal = (SELECT COALESCE(SUM(ROUND(l.qty * l.unit_price, 2)), 0)
                       FROM invoice_line_item l WHERE l.invoice_id = i.id),
    rework_taxes = (SELECT COALESCE(SUM(
                        ROUND((ROUND(l.qty * l.unit_price, 2)
                               - ROUND(ROUND(l.qty * l.unit_price, 2) * i.discount_pct / 100, 2)) * i.tax_pct / 100, 2)
                        + ROUND((ROUND(l.qty * l.unit_price, 2)
                               - ROUND(ROUND(l.qty * l.unit_price, 2) * i.discount_pct / 100, 2)) * i.levy_pct / 100, 2)), 0)
                    FROM invoice_line_item l WHERE l.invoice_id = i.id AND NOT l.tax_exempt)
WHERE i.status = 'DRAFT'
  AND NOT i.tax_inclusive
  AND EXISTS (SELECT 1 FROM invoice_line_item l WHERE l.invoice_id = i.id);

UPDATE invoice SET amount = rework_subtotal - ROUND(rework_subtotal * discount_pct / 100, 2) + rework_taxes
WHERE rework_subtotal IS NOT NULL;

ALTER TABLE invoice DROP COLUMN rework_subtotal;
ALTER TABLE invoice DROP COLUMN rework_taxes;
