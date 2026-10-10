-- An invoice can carry the client's purchase-order number; unset when none was given.
ALTER TABLE invoice ADD COLUMN po_number VARCHAR(50);
