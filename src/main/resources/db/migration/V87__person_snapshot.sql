-- A person's snapshots: one per SNAPSHOT written for them, each the day being left and their
-- due-total on it (text with two decimals, or "n/a"), kept in the order they were written.
CREATE TABLE person_snapshot (
    person_id BIGINT       NOT NULL REFERENCES person (id),
    position  INT          NOT NULL,
    left_on   DATE         NOT NULL,
    due_total VARCHAR(20)  NOT NULL,
    PRIMARY KEY (person_id, position)
);
