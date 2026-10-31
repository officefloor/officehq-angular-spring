-- The people a user watches on a sheet: each user watches a person at most once. Ids rise in the order users
-- started watching.
CREATE TABLE watch (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    user_name VARCHAR(100) NOT NULL
);
CREATE UNIQUE INDEX watch_pair ON watch (person_id, user_name);
CREATE INDEX watch_sheet_user ON watch (sheet_id, user_name);
