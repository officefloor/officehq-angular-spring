-- The columns of the people list each user has switched off on their screen. A user without rows
-- sees every column. Kept per user, across every sheet.
CREATE TABLE user_hidden_column (
    user_name  VARCHAR(200) NOT NULL,
    column_key VARCHAR(40)  NOT NULL,
    PRIMARY KEY (user_name, column_key)
);
