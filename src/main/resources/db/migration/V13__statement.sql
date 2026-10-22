-- A person's statements: how many have been taken, and the due-total and date (the sheet's today) of
-- the last one. The last due-total is null with no statement, or when it was n/a.
ALTER TABLE person ADD COLUMN statement_count INT DEFAULT 0 NOT NULL;
ALTER TABLE person ADD COLUMN statement_total DECIMAL(14, 2);
ALTER TABLE person ADD COLUMN statement_date DATE;
-- A claim bound by its person's last statement: stated, with the due figure it had then (null when
-- it was n/a). A claim that did not count at the statement is left unbound, its due live again.
ALTER TABLE claim ADD COLUMN stated BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE claim ADD COLUMN stated_due DECIMAL(14, 2);
