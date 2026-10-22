-- The permission matrix: for an action (open, settle, void, reassign, close, reopen), the roles that
-- may take it, as a comma separated list. An action with no row keeps its standing rule.
CREATE TABLE sheet_permission (
    sheet_id    VARCHAR(64) NOT NULL REFERENCES sheet (id),
    action      VARCHAR(16) NOT NULL,
    roles       VARCHAR(64) NOT NULL
);
CREATE UNIQUE INDEX sheet_permission_action ON sheet_permission (sheet_id, action);
