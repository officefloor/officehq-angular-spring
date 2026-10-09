-- A client may have a phone number to reach them on; existing clients have none.
ALTER TABLE client ADD COLUMN phone VARCHAR(50);
