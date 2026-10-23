-- Each time the sheet's today moves on, each person's due-total as it stood on the day being left
-- (null: n/a) is kept as one of their snapshot lines, oldest first.
CREATE TABLE person_snapshot (
    person_id      BIGINT        NOT NULL REFERENCES person (id),
    snapshot_order INT           NOT NULL,
    as_of          DATE          NOT NULL,
    due_total      DECIMAL(14,2),
    PRIMARY KEY (person_id, snapshot_order)
);
