package net.officefloor.hq.app.invoices;

/** How a payment was made: a bank transfer, or cash counted out in coins (see {@code Currency#cashStep()}). */
public enum PaymentMethod {
    BANK, CASH;

    /** The method with the name, or null when there is no such method. */
    public static PaymentMethod of(String name) {
        for (PaymentMethod m : values()) {
            if (m.name().equals(name)) {
                return m;
            }
        }
        return null;
    }
}
