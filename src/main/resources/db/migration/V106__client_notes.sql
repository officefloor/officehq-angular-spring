-- Clients take notes as well as jobs (projects) and invoices, read back as a timeline, newest first.
ALTER TABLE note DROP CONSTRAINT note_target_type_ck;
ALTER TABLE note ADD CONSTRAINT note_target_type_ck CHECK (target_type IN ('project', 'invoice', 'client'));
