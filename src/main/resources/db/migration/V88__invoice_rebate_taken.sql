-- The settlement rebate actually taken on an invoice: once the client has paid the amount less the rebate before
-- the due date, the rebate is given back and the invoice is settled for the reduced amount. Nothing until then.
ALTER TABLE invoice ADD COLUMN rebate_taken DECIMAL(12, 2) DEFAULT 0 NOT NULL;

-- Invoices already paid down to the reduced amount before their due date take their rebate.
UPDATE invoice i SET rebate_taken = ROUND(i.amount * i.rebate_pct / 100, 2)
WHERE i.rebate_pct > 0 AND i.due_date IS NOT NULL AND i.status IN ('SENT', 'PARTIAL')
  AND COALESCE((SELECT SUM(p.amount) FROM payment p WHERE p.invoice_id = i.id AND p.paid_date < i.due_date), 0)
    + COALESCE((SELECT SUM(c.amount) FROM credit_note c WHERE c.invoice_id = i.id), 0)
    + i.write_off_amount >= i.amount - ROUND(i.amount * i.rebate_pct / 100, 2);

ALTER TABLE invoice ADD CONSTRAINT invoice_rebate_taken_not_negative CHECK (rebate_taken >= 0);
