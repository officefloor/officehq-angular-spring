-- An invoice can be issued after the date payment was already due (raised late for work that was due earlier), so
-- the database no longer insists the due date is on or after the issue date. Invoices created through the app are
-- still checked by the service.
ALTER TABLE invoice DROP CONSTRAINT invoice_due_after_issued;
