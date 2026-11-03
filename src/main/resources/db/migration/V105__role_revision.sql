-- Each role given to a user name on a sheet, stamped with the sheet's today then, so the role a
-- user had on a past day is the last one given on or before it. Roles given before revisions were
-- kept count from the start.
CREATE TABLE role_revision (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id    VARCHAR(100) NOT NULL REFERENCES sheet (id),
    user_name   VARCHAR(100) NOT NULL,
    role        VARCHAR(20)  NOT NULL,
    recorded_on DATE         NOT NULL
);
CREATE INDEX role_revision_sheet_user ON role_revision (sheet_id, user_name);
INSERT INTO role_revision (sheet_id, user_name, role, recorded_on)
    SELECT sheet_id, user_name, role, DATE '0001-01-01' FROM sheet_role;
