-- The early-payment discount window is now part of a client's payment terms rather than a fixed number of
-- days set on each invoice: an invoice offering an early-payment discount must be paid within its client's
-- early-payment window of being issued. The window lies within the client's payment terms, so it needs terms.
ALTER TABLE client ADD COLUMN early_payment_window_days INTEGER;

-- Carry over the longest window a client's invoices offered, kept within their payment terms.
UPDATE client c SET early_payment_window_days = (
    SELECT LEAST(MAX(i.early_payment_days), c.payment_terms_days)
    FROM invoice i JOIN project p ON p.id = i.project_id
    WHERE p.client_id = c.id AND i.early_payment_days > 0)
WHERE c.payment_terms_days IS NOT NULL;

ALTER TABLE client ADD CONSTRAINT client_early_payment_window_within_terms CHECK (early_payment_window_days IS NULL
    OR (early_payment_window_days BETWEEN 0 AND 365 AND payment_terms_days IS NOT NULL AND early_payment_window_days <= payment_terms_days));

ALTER TABLE invoice DROP CONSTRAINT invoice_early_payment_days_non_negative;
ALTER TABLE invoice DROP COLUMN early_payment_days;
