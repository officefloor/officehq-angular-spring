-- Each setting of a user name's role on a sheet, with the sheet's today it was set on, so the roles
-- can be read as they stood at the end of a past date. Roles set before this was kept have no date:
-- they are taken as in force on every date.
CREATE TABLE sheet_role_history (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(64)  NOT NULL REFERENCES sheet (id),
    user_name VARCHAR(200) NOT NULL,
    role      VARCHAR(16)  NOT NULL,
    set_on    DATE
);
CREATE INDEX sheet_role_history_sheet ON sheet_role_history (sheet_id, id);
INSERT INTO sheet_role_history (sheet_id, user_name, role, set_on)
    SELECT sheet_id, user_name, role, NULL FROM sheet_role ORDER BY id;
