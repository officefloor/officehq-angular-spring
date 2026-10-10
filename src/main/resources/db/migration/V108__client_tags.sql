-- Clients can be labelled with the same shared tags as projects, so they can be grouped; several
-- clients can be tagged at once. Tagging belongs to the client and goes with it if it is deleted.
CREATE TABLE client_tag (
    client_id BIGINT NOT NULL,
    tag_id    BIGINT NOT NULL,
    CONSTRAINT client_tag_pk PRIMARY KEY (client_id, tag_id),
    CONSTRAINT client_tag_client_fk FOREIGN KEY (client_id) REFERENCES client (id) ON DELETE CASCADE,
    CONSTRAINT client_tag_tag_fk FOREIGN KEY (tag_id) REFERENCES tag (id) ON DELETE CASCADE
);

CREATE INDEX client_tag_tag_idx ON client_tag (tag_id);
