-- An adjustment note carries the purchase order number of the invoice it adjusts.
UPDATE invoice n SET po_number = (SELECT i.po_number FROM invoice i WHERE i.id = n.adjustment_of)
    WHERE n.adjustment_of IS NOT NULL AND n.po_number IS NULL;
