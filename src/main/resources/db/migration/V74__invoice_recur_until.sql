-- The last day a recurring invoice bills for: a generated period stops on it, and none starting after it is generated; null for no end.
ALTER TABLE invoice ADD COLUMN recur_until DATE;
