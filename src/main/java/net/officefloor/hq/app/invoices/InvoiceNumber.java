package net.officefloor.hq.app.invoices;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An invoice's number, made from its id: INV-, the id padded to six digits, then a Luhn check
 * digit over those six digits — so invoice 7 is INV-0000075.
 */
public final class InvoiceNumber {

    private static final Pattern FORM = Pattern.compile("(?:INV|CN)-(\\d{6})(\\d)");

    private InvoiceNumber() {
    }

    /** The number of the invoice with this id. */
    public static String of(long id) {
        String digits = String.format("%06d", id);
        return "INV-" + digits + checkDigit(digits);
    }

    /** The number of the credit note with this id: CN-, then the same digits as an invoice number. */
    public static String creditNoteOf(long id) {
        return "CN-" + of(id).substring("INV-".length());
    }

    /**
     * The invoice id a typed number stands for. Empty when it is not of the form INV-nnnnnnn (or CN-nnnnnnn) or its
     * check digit does not match — the number was mistyped.
     */
    public static Optional<Long> idOf(String typed) {
        Matcher m = FORM.matcher(typed.trim().toUpperCase());
        if (!m.matches() || checkDigit(m.group(1)) != m.group(2).charAt(0) - '0') {
            return Optional.empty();
        }
        return Optional.of(Long.parseLong(m.group(1)));
    }

    /**
     * Luhn: from the rightmost digit, double every second digit (the rightmost, the third from the
     * right, ...), subtracting 9 from any result over 9; the check digit brings the sum of all the
     * digits up to the next multiple of ten.
     */
    static int checkDigit(String digits) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            int d = digits.charAt(digits.length() - 1 - i) - '0';
            if (i % 2 == 0) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
        }
        return (10 - sum % 10) % 10;
    }
}
