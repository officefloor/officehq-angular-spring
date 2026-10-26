-- The users watching a person on a sheet, each at most once; the id orders them by when they started watching.
CREATE TABLE person_watch (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    user_name VARCHAR(100) NOT NULL
);
CREATE UNIQUE INDEX person_watch_once ON person_watch (person_id, user_name);
CREATE INDEX person_watch_sheet ON person_watch (sheet_id, user_name);
