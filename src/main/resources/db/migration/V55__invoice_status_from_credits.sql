-- An invoice's status is now worked out from its payments AND its credit notes: a credit that clears
-- what is left settles the invoice rather than leaving it owing. Bring sent and part-paid invoices in
-- line with what has already been paid and credited against them.
UPDATE invoice i SET status = CASE
        WHEN COALESCE((SELECT SUM(p.amount) FROM payment p WHERE p.invoice_id = i.id), 0)
           + COALESCE((SELECT SUM(c.amount) FROM credit_note c WHERE c.invoice_id = i.id), 0) >= i.amount THEN 'PAID'
        ELSE 'PARTIAL'
    END
WHERE i.status IN ('SENT', 'PARTIAL') AND EXISTS (SELECT 1 FROM credit_note c WHERE c.invoice_id = i.id);
