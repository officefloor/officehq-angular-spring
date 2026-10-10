-- An instalment is marked paid once it has been settled, so the next one due can be told apart.
ALTER TABLE invoice_instalment ADD COLUMN paid BOOLEAN DEFAULT FALSE NOT NULL;
