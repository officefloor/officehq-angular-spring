-- The levy (the second tax) is scrapped: invoices carry only the one sales tax again, and every total
-- works out as if the levy never existed. An invoice that added a levy on top has it taken back out of
-- its stored amount (never below its minimum charge): the levy on each taxable line after its share of
-- the discounts, rounded to the cent and added up, just as it was charged. A tax-inclusive invoice owes
-- the same (the levy was inside its prices, and now only the sales tax is worked back out of them), and
-- a tax-exempt invoice never carried a levy.
CREATE VIEW invoice_levy_line_v109 AS
    SELECT l.invoice_id,
           l.tax_exempt,
           ROUND(l.qty * l.unit_price, 2) - ROUND(ROUND(l.qty * l.unit_price, 2) * l.discount_pct / 100, 2) AS line_amount
    FROM invoice_line_item l;

CREATE VIEW invoice_levy_subtotal_v109 AS
    SELECT i.id AS invoice_id,
           COALESCE((SELECT SUM(v.line_amount) FROM invoice_levy_line_v109 v WHERE v.invoice_id = i.id), 0) AS subtotal
    FROM invoice i;

-- The uncapped percentages each line takes off itself, and what the flat amounts and capped percentages
-- take off the subtotal together, shared across the lines in proportion to what each charges.
CREATE VIEW invoice_levy_discount_v109 AS
    SELECT s.invoice_id,
           s.subtotal,
           LEAST(COALESCE((SELECT SUM(d.discount_pct) FROM invoice_discount d WHERE d.invoice_id = s.invoice_id
                             AND d.discount_pct > 0
                             AND (d.discount_cap IS NULL OR ROUND(s.subtotal * d.discount_pct / 100, 2) <= d.discount_cap)), 0),
                 100) AS uncapped_pct,
           COALESCE((SELECT SUM(CASE WHEN d.discount_pct > 0 THEN d.discount_cap ELSE d.discount_amount END)
                     FROM invoice_discount d WHERE d.invoice_id = s.invoice_id
                       AND (d.discount_pct = 0
                            OR (d.discount_cap IS NOT NULL AND ROUND(s.subtotal * d.discount_pct / 100, 2) > d.discount_cap))), 0)
               AS shared_wanted
    FROM invoice_levy_subtotal_v109 s;

ALTER TABLE invoice ADD COLUMN rework_levy DECIMAL(14, 2);

UPDATE invoice i SET rework_levy = (
    SELECT COALESCE(SUM(ROUND(
               (v.line_amount
                - ROUND(v.line_amount * d.uncapped_pct / 100, 2)
                - CASE WHEN d.subtotal = 0 THEN 0
                       ELSE ROUND(LEAST(d.shared_wanted,
                                        GREATEST(d.subtotal - ROUND(d.subtotal * d.uncapped_pct / 100, 2), 0))
                                  * v.line_amount / d.subtotal, 2) END)
               * i.levy_pct / 100, 2)), 0)
    FROM invoice_levy_line_v109 v JOIN invoice_levy_discount_v109 d ON d.invoice_id = v.invoice_id
    WHERE v.invoice_id = i.id AND NOT v.tax_exempt)
WHERE i.levy_pct > 0
  AND NOT i.tax_inclusive
  AND NOT i.tax_exempt;

UPDATE invoice SET amount = GREATEST(amount - rework_levy, minimum_charge)
WHERE rework_levy IS NOT NULL AND rework_levy > 0;

ALTER TABLE invoice DROP COLUMN rework_levy;

DROP VIEW invoice_levy_discount_v109;
DROP VIEW invoice_levy_subtotal_v109;
DROP VIEW invoice_levy_line_v109;

ALTER TABLE invoice DROP CONSTRAINT invoice_levy_pct_range;
ALTER TABLE invoice DROP COLUMN levy_pct;
