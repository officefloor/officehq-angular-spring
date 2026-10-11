-- A client's rate card: its price per unit type (hour, day, ...).
CREATE TABLE client_rate (
    client_id  BIGINT         NOT NULL REFERENCES client (id) ON DELETE CASCADE,
    unit_type  VARCHAR(64)    NOT NULL,
    unit_price DECIMAL(19, 2) NOT NULL,
    PRIMARY KEY (client_id, unit_type)
);

-- The unit type a line is billed in (hour, day, ...); NULL for none.
ALTER TABLE invoice_line ADD COLUMN unit_type VARCHAR(64);
