-- A sheet's permission matrix: for an action (open, settle, void, reassign, close, reopen) the roles
-- that may take it, a comma separated list of clerk and steward. An action with no row keeps the
-- standing rule: open, settle, void and close by clerk and steward; reassign and reopen by steward.
CREATE TABLE sheet_permission (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    action VARCHAR(20) NOT NULL,
    roles VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX sheet_permission_sheet_action ON sheet_permission (sheet_id, action);
