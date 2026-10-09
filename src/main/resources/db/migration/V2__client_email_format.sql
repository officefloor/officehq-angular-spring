-- Every client must have a well-formed email (local@domain.tld); backstop for the API validation.
ALTER TABLE client ADD CONSTRAINT client_email_format
    CHECK (email LIKE '_%@_%._%' AND email NOT LIKE '% %');
