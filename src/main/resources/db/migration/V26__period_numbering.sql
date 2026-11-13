-- A sheet's period numbering from: while it is set and the sheet's today is on or after it, a claim
-- opened is numbered within its period (<period>-<n>), or X-<n> when its date falls in no period.
-- Null (blank) is off: claims take the sheet-wide number. Claim numbers, and the numbers corrections
-- name, become text so they can hold either form.
ALTER TABLE sheet ADD COLUMN period_numbering_from DATE;
ALTER TABLE claim ALTER COLUMN claim_number SET DATA TYPE VARCHAR(255);
ALTER TABLE claim ALTER COLUMN corrects_number SET DATA TYPE VARCHAR(255);
