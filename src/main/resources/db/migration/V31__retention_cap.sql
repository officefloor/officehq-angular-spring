-- The most retention an invoice holds back, in its currency; null is no cap.
ALTER TABLE invoice ADD COLUMN retention_cap DECIMAL(19, 2);
