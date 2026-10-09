-- The currencies clients can be billed in, each with the symbol its amounts are shown with and the step
-- its amounts are rounded to when shown (0.01 rounds to the cent; 0.05 to the nearest five cents).
CREATE TABLE currency (
    code VARCHAR(3) PRIMARY KEY,
    symbol VARCHAR(8) NOT NULL,
    rounding_step DECIMAL(10, 2) DEFAULT 0.01 NOT NULL,
    CONSTRAINT currency_rounding_step_positive CHECK (rounding_step > 0)
);

INSERT INTO currency (code, symbol, rounding_step) VALUES
    ('USD', '$', 0.01),
    ('EUR', '€', 0.01),
    ('GBP', '£', 0.01),
    ('CAD', 'CA$', 0.01),
    ('AUD', 'A$', 0.01);

-- A client's currency is now any known currency rather than a fixed list.
ALTER TABLE client DROP CONSTRAINT client_currency_known;
ALTER TABLE client ADD CONSTRAINT client_currency_fk FOREIGN KEY (currency) REFERENCES currency (code);
