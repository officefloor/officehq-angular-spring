-- The columns of the people list each user has switched off on their screen, kept per user across every
-- sheet. A column with no row is shown.
CREATE TABLE user_hidden_column (
    user_name  VARCHAR(100) NOT NULL,
    column_key VARCHAR(40)  NOT NULL,
    PRIMARY KEY (user_name, column_key)
);
