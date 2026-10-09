-- A tax-registered client has a tax number, shown on their invoices; existing clients have none.
ALTER TABLE client ADD COLUMN tax_number VARCHAR(50);
