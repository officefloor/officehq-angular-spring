-- Closing or soft-closing a period fixes each pool member's cut for it, as it stood then, by the member's
-- key (its pool's view id and the member: p<person id> or q<pool id>); reopening clears them. A member with
-- no row here has no cut for a closed period.
CREATE TABLE period_pool_cut (
    period_id  BIGINT         NOT NULL REFERENCES period (id),
    member_key VARCHAR(100)   NOT NULL,
    cut        DECIMAL(15, 2) NOT NULL,
    PRIMARY KEY (period_id, member_key)
);
