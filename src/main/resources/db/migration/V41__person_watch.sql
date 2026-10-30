-- The users watching a person on a sheet, each at most once, in the order they started watching (by id).
CREATE TABLE person_watch (
    id        BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id  VARCHAR(100) NOT NULL REFERENCES sheet (id),
    person_id BIGINT       NOT NULL REFERENCES person (id),
    user_name VARCHAR(200) NOT NULL,
    CONSTRAINT person_watch_unique UNIQUE (person_id, user_name)
);
CREATE INDEX person_watch_sheet ON person_watch (sheet_id, user_name);
