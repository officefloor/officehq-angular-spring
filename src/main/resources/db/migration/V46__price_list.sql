-- The workspace price list, in US dollars, for unit types a client's own rate card doesn't cover:
-- a single price per unit type, or tiers in the same form as client_rate.tiers.
CREATE TABLE price_list_rate (
    unit_type  VARCHAR(64)    NOT NULL PRIMARY KEY,
    unit_price DECIMAL(19, 2),
    tiers      VARCHAR(1024)
);
