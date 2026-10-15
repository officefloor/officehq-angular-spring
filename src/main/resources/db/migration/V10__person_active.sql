-- Whether a person is active; a new person is active until marked otherwise.
ALTER TABLE person ADD COLUMN active BOOLEAN DEFAULT TRUE NOT NULL;
