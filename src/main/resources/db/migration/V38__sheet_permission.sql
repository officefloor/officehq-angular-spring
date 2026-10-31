-- The sheet's permission matrix: for an action (open, settle, void, reassign, close, reopen), the roles that may
-- take it, as a comma separated list. Not dated. An action with no row keeps the standing rule: open, settle, void
-- and close by clerk and steward; reassign and reopen by steward.
CREATE TABLE sheet_permission (
    sheet_id VARCHAR(100) NOT NULL REFERENCES sheet (id),
    action   VARCHAR(20)  NOT NULL,
    roles    VARCHAR(100) NOT NULL,
    CONSTRAINT sheet_permission_action UNIQUE (sheet_id, action)
);
