-- An invoice's status is worked out from what is genuinely still owed after any part written off. A part
-- write-off never clears the whole balance (that writes off the invoice), so a sent invoice with a part
-- written off has had something settled and is part-paid.
UPDATE invoice SET status = 'PARTIAL' WHERE status = 'SENT' AND write_off_amount > 0;
