-- The sheet's permission matrix: for an action (open, settle, void, reassign, close, reopen), the roles
-- that may take it, as a comma separated list of lower-case roles. Not dated. An action with no row, or
-- a blank list, keeps the standing rule: open, settle, void and close by clerk and steward; reassign and
-- reopen by steward.
CREATE TABLE sheet_permission (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    perm_action VARCHAR(20)  NOT NULL,
    roles     VARCHAR(100) NOT NULL
);
CREATE UNIQUE INDEX sheet_permission_sheet_action ON sheet_permission (sheet_id, perm_action);
