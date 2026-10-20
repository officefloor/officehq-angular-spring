-- A sheet's permission matrix: for an action (open, settle, void, reassign, close, reopen), the roles that may
-- take it, comma separated. An action with no row keeps the standing rule.
CREATE TABLE sheet_permission (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    action   VARCHAR(20)  NOT NULL,
    roles    VARCHAR(100) NOT NULL,
    PRIMARY KEY (sheet_id, action)
);
