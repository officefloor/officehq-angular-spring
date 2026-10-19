-- A voided claim now keeps the number it was opened with; the number is still never reused, as the
-- sheet's last_claim_number only grows. Claims voided before this change have no number left to restore.
-- Only a DRAFT claim can be edited; an open claim is voided and added again instead.
SELECT 1;
