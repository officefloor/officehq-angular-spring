-- Why the invoice discount was given; needed when it takes off more than a fifth of the subtotal. NULL when none.
ALTER TABLE invoice ADD COLUMN discount_reason VARCHAR(500);
