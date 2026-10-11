-- A client quoted prices that already include tax: the tax is taken out of its invoice amounts
-- rather than added on top; prices exclude tax by default.
ALTER TABLE client ADD COLUMN prices_include_tax BOOLEAN DEFAULT FALSE NOT NULL;
