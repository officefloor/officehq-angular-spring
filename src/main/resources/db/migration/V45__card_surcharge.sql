-- The card surcharge a card payment carried: the part of its amount that is the workspace's card
-- surcharge, which pays no invoice and is held as no credit; 0 for any other payment.
ALTER TABLE payment ADD COLUMN surcharge DECIMAL(19, 2) DEFAULT 0 NOT NULL;
