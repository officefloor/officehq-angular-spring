-- Each client can have one main contact, chosen from its own contacts. The composite key makes the
-- database refuse a main contact that belongs to another client.
ALTER TABLE contact ADD CONSTRAINT contact_id_client_uq UNIQUE (id, client_id);
ALTER TABLE client ADD COLUMN primary_contact_id BIGINT;
ALTER TABLE client ADD CONSTRAINT client_primary_contact_fk
    FOREIGN KEY (primary_contact_id, id) REFERENCES contact (id, client_id);
