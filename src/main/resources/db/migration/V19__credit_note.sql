-- A credit note is an invoice crediting another (credit_of, the original's id); once issued its grand
-- total is kept in credit_total and comes off what the original has due.
ALTER TABLE invoice ADD COLUMN credit_of BIGINT;
ALTER TABLE invoice ADD COLUMN credit_total DECIMAL(19, 2);
