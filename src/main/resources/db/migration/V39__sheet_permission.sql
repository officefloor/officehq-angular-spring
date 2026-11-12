-- The permission matrix: for an action (open, settle, void, reassign, close, reopen), the roles that may do
-- it, as a comma separated list. An action with no row keeps the standing rule: open, settle, void and close
-- by clerk and steward; reassign and reopen by steward. Not dated.
CREATE TABLE sheet_permission (
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    action   VARCHAR(20)  NOT NULL,
    roles    VARCHAR(200) NOT NULL,
    PRIMARY KEY (sheet_id, action)
);
