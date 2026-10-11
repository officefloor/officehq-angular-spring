-- Whether the due date was set by hand on the draft; such a due date is kept when the invoice is sent.
ALTER TABLE invoice ADD COLUMN manual_due_date BOOLEAN DEFAULT FALSE NOT NULL;
