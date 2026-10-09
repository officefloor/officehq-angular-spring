-- An invoice's status is now worked out from its payments rather than set by hand: a sent invoice
-- becomes PARTIAL once something has been paid against it and PAID once its payments cover it.
ALTER TABLE invoice DROP CONSTRAINT invoice_status_valid;
ALTER TABLE invoice ADD CONSTRAINT invoice_status_valid CHECK (status IN ('DRAFT', 'SENT', 'PARTIAL', 'PAID'));

-- Bring sent invoices in line with the payments already recorded against them. Invoices that were
-- marked paid by hand stay PAID.
UPDATE invoice i SET status = CASE
        WHEN (SELECT SUM(p.amount) FROM payment p WHERE p.invoice_id = i.id) >= i.amount THEN 'PAID'
        ELSE 'PARTIAL'
    END
WHERE i.status = 'SENT' AND EXISTS (SELECT 1 FROM payment p WHERE p.invoice_id = i.id);
