-- The people each user watches, each at most once per user; a watch's id orders a person's
-- watchers by when they started watching.
CREATE TABLE person_watch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sheet_id VARCHAR(200) NOT NULL REFERENCES sheet (id),
    person_id BIGINT NOT NULL REFERENCES person (id),
    user_name VARCHAR(200) NOT NULL
);
CREATE UNIQUE INDEX person_watch_pair ON person_watch (person_id, user_name);
CREATE INDEX person_watch_sheet ON person_watch (sheet_id, user_name);
