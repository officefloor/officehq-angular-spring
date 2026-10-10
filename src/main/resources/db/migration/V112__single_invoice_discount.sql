-- An invoice carries only one discount again. An invoice with several keeps what they took off together,
-- so its amount is unchanged: when they were all uncapped percentages they become one percentage (their
-- sum, never more than 100); otherwise they become one flat amount equal to what they took off between
-- them (the percentages' shares, each no more than its cap, plus the flat amounts, never more than the
-- subtotal).
CREATE VIEW invoice_discount_subtotal_v112 AS
    SELECT i.id AS invoice_id,
           COALESCE((SELECT SUM(ROUND(l.qty * l.unit_price, 2)
                         - ROUND(ROUND(l.qty * l.unit_price, 2) * l.discount_pct / 100, 2))
                     FROM invoice_line_item l WHERE l.invoice_id = i.id), 0) AS subtotal
    FROM invoice i
    WHERE (SELECT COUNT(*) FROM invoice_discount d WHERE d.invoice_id = i.id) > 1;

CREATE TABLE invoice_discount_v112 (
    invoice_id      BIGINT         NOT NULL PRIMARY KEY,
    discount_pct    DECIMAL(5, 2)  NOT NULL,
    discount_amount DECIMAL(12, 2) NOT NULL
);

INSERT INTO invoice_discount_v112 (invoice_id, discount_pct, discount_amount)
SELECT s.invoice_id,
       CASE WHEN all_pct THEN LEAST(pct_sum, 100) ELSE 0 END,
       CASE WHEN all_pct THEN 0 ELSE LEAST(GREATEST(s.subtotal, 0), pct_taken + flat_sum) END
FROM (SELECT s.invoice_id,
             s.subtotal,
             NOT EXISTS (SELECT 1 FROM invoice_discount d WHERE d.invoice_id = s.invoice_id
                           AND (d.discount_pct = 0 OR d.discount_cap IS NOT NULL)) AS all_pct,
             (SELECT SUM(d.discount_pct) FROM invoice_discount d WHERE d.invoice_id = s.invoice_id) AS pct_sum,
             (SELECT COALESCE(SUM(CASE WHEN d.discount_cap IS NULL THEN ROUND(s.subtotal * d.discount_pct / 100, 2)
                                       ELSE LEAST(ROUND(s.subtotal * d.discount_pct / 100, 2), d.discount_cap) END), 0)
              FROM invoice_discount d WHERE d.invoice_id = s.invoice_id AND d.discount_pct > 0) AS pct_taken,
             (SELECT COALESCE(SUM(d.discount_amount), 0) FROM invoice_discount d WHERE d.invoice_id = s.invoice_id) AS flat_sum
      FROM invoice_discount_subtotal_v112 s) s;

DELETE FROM invoice_discount WHERE invoice_id IN (SELECT invoice_id FROM invoice_discount_v112);

-- An invoice whose discounts took nothing off (an empty invoice) is left with none.
INSERT INTO invoice_discount (invoice_id, discount_pct, discount_amount)
SELECT invoice_id, discount_pct, discount_amount FROM invoice_discount_v112
WHERE discount_pct > 0 OR discount_amount > 0;

DROP TABLE invoice_discount_v112;
DROP VIEW invoice_discount_subtotal_v112;

ALTER TABLE invoice_discount ADD CONSTRAINT invoice_discount_one_per_invoice UNIQUE (invoice_id);
