-- An invoice's discount is now either a percentage or a flat amount, never both. An invoice that had
-- both keeps what they took off together, as a flat amount, so its amount is unchanged: the
-- percentage of the subtotal plus the flat amount (never more than was left after the percentage).
CREATE VIEW invoice_subtotal_v44 AS
    SELECT i.id AS invoice_id,
           COALESCE((SELECT SUM(ROUND(l.qty * l.unit_price, 2)
                         - ROUND(ROUND(l.qty * l.unit_price, 2) * l.discount_pct / 100, 2))
                     FROM invoice_line_item l WHERE l.invoice_id = i.id), 0) AS subtotal
    FROM invoice i;

UPDATE invoice i SET
    discount_amount = (
        SELECT ROUND(v.subtotal * i.discount_pct / 100, 2)
            + LEAST(i.discount_amount, GREATEST(v.subtotal - ROUND(v.subtotal * i.discount_pct / 100, 2), 0))
        FROM invoice_subtotal_v44 v WHERE v.invoice_id = i.id),
    discount_pct = 0
WHERE i.discount_pct > 0 AND i.discount_amount > 0;

DROP VIEW invoice_subtotal_v44;

ALTER TABLE invoice ADD CONSTRAINT invoice_discount_one_kind CHECK (discount_pct = 0 OR discount_amount = 0);
