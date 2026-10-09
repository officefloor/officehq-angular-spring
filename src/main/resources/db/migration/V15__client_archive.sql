-- Clients are archived rather than deleted: an archived client drops off the client list and search
-- but is kept, with its projects and contacts, and can be brought back.
ALTER TABLE client ADD COLUMN archived BOOLEAN NOT NULL DEFAULT FALSE;
