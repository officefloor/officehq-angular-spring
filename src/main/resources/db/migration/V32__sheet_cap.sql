-- The sheet's cap: a person's due-total is at most it, the excess shown apart. Null means no cap.
ALTER TABLE sheet ADD COLUMN cap DECIMAL(14, 2);
