package net.officefloor.hq.app.invoices;

import java.math.BigInteger;
import java.util.Optional;

/**
 * An invoice's ISO 11649 "RF" creditor reference, made from the seven digits of its number (leading
 * zeros kept): RF, two check digits, then the digits, so invoice 7 (INV-0000075) is RF160000075,
 * shown in groups of four as RF16 0000 075.
 */
public final class PaymentReference {

    private static final BigInteger NINETY_SEVEN = BigInteger.valueOf(97);

    private PaymentReference() {
    }

    /** The reference of the invoice with this id, without spaces. */
    public static String of(long id) {
        String digits = InvoiceNumber.of(id).substring("INV-".length());
        int check = 98 - mod97(digits + "RF00");
        return String.format("RF%02d", check) + digits;
    }

    /** The reference in groups of four, e.g. RF16 0000 075. */
    public static String grouped(String reference) {
        String compact = compact(reference);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < compact.length(); i += 4) {
            if (i > 0) {
                out.append(' ');
            }
            out.append(compact, i, Math.min(i + 4, compact.length()));
        }
        return out.toString();
    }

    /**
     * A typed reference without spaces and in capitals. Empty when it is not RF, two digits and up to
     * 21 letters or digits, or it fails the check (its first four characters moved to the end,
     * letters as numbers, mod 97 must be 1) — the reference was mistyped.
     */
    public static Optional<String> parse(String typed) {
        String compact = compact(typed);
        if (!compact.matches("RF\\d{2}[A-Z0-9]{1,21}")
                || mod97(compact.substring(4) + compact.substring(0, 4)) != 1) {
            return Optional.empty();
        }
        return Optional.of(compact);
    }

    private static String compact(String reference) {
        return reference.replaceAll("\\s", "").toUpperCase();
    }

    /** Letters as numbers (A=10 up to Z=35), then the whole number mod 97. */
    private static int mod97(String text) {
        StringBuilder number = new StringBuilder();
        for (char c : text.toCharArray()) {
            number.append(Character.getNumericValue(c));
        }
        return new BigInteger(number.toString()).mod(NINETY_SEVEN).intValue();
    }
}
