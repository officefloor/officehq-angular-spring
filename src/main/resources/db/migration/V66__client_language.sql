-- A client may have a language they prefer to be dealt with in; existing clients have none recorded.
ALTER TABLE client ADD COLUMN language VARCHAR(50);
