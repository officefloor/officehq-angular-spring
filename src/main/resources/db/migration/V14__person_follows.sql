-- Allies are now one-directional: a person follows another without being followed back. Each former
-- (two-way) ally pair becomes a follow in both directions, so everyone keeps the allies they had.
CREATE TABLE person_follow (
    follower_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    followed_id BIGINT NOT NULL REFERENCES person (id) ON DELETE CASCADE,
    PRIMARY KEY (follower_id, followed_id)
);

INSERT INTO person_follow (follower_id, followed_id) SELECT person_id, ally_id FROM person_ally;
INSERT INTO person_follow (follower_id, followed_id)
    SELECT ally_id, person_id FROM person_ally pa
    WHERE NOT EXISTS (SELECT 1 FROM person_ally r WHERE r.person_id = pa.ally_id AND r.ally_id = pa.person_id);

DROP TABLE person_ally;
