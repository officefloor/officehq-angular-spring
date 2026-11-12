-- A voided claim now keeps the number it was given when opened (still never reused); only a draft
-- has none. Only a DRAFT claim can be edited. No change to the columns is needed.
SELECT 1;
