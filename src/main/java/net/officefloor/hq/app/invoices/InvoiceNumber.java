package net.officefloor.hq.app.invoices;

import java.time.LocalDate;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An invoice's number, made from its id: INV-, the id padded to six digits, then a check character
 * over those six digits. Numbered before 2026-07-01 that is a Luhn check digit — so invoice 7 is
 * INV-0000075; from then on it is an ISO 7064 MOD 11-2 check character (0-9 or X) — so invoice 28 is
 * INV-000028X. The six digits and the check character are the number's "body".
 */
public final class InvoiceNumber {

    /** From this date numbers take a MOD 11-2 check character instead of the Luhn digit. */
    public static final LocalDate MOD_11_2_FROM = LocalDate.of(2026, 7, 1);

    private static final Pattern FORM = Pattern.compile("(?:INV|CN|ADJ)-(\\d{6})([0-9X])");

    private InvoiceNumber() {
    }

    /** The six digits and check character of the invoice with this id, numbered on the date. */
    public static String body(long id, LocalDate numberedOn) {
        String digits = String.format("%06d", id);
        boolean mod112 = numberedOn != null && !numberedOn.isBefore(MOD_11_2_FROM);
        return digits + (mod112 ? mod112Check(digits) : (char) ('0' + checkDigit(digits)));
    }

    /** The number of the invoice with this id, numbered on the date. */
    public static String of(long id, LocalDate numberedOn) {
        return "INV-" + body(id, numberedOn);
    }

    /** The number of the credit note with this id: CN-, then the same body as an invoice number. */
    public static String creditNoteOf(long id, LocalDate numberedOn) {
        return "CN-" + body(id, numberedOn);
    }

    /** The number of the adjustment note with this id: ADJ-, then the same body as an invoice number. */
    public static String adjustmentOf(long id, LocalDate numberedOn) {
        return "ADJ-" + body(id, numberedOn);
    }

    /**
     * The body (six digits and check character) of a typed number. Empty when it is not of the form
     * INV-nnnnnnc (or CN-, ADJ-) or its check character matches neither the Luhn nor the MOD 11-2
     * check — the number was mistyped. Whether it is the number of an invoice is for the caller to say.
     */
    public static Optional<String> bodyOf(String typed) {
        Matcher m = FORM.matcher(typed.trim().toUpperCase());
        if (!m.matches()) {
            return Optional.empty();
        }
        String digits = m.group(1);
        char check = m.group(2).charAt(0);
        if (check != '0' + checkDigit(digits) && check != mod112Check(digits)) {
            return Optional.empty();
        }
        return Optional.of(digits + check);
    }

    /** The invoice id a typed number stands for; empty when it is mistyped (see {@link #bodyOf}). */
    public static Optional<Long> idOf(String typed) {
        return bodyOf(typed).map(body -> Long.parseLong(body.substring(0, 6)));
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

    /**
     * ISO 7064 MOD 11-2: p starts at 0 and for each digit becomes (p + digit) x 2 mod 11; the check
     * is (12 - p) mod 11, written X when it is 10.
     */
    static char mod112Check(String digits) {
        int p = 0;
        for (char c : digits.toCharArray()) {
            p = (p + (c - '0')) * 2 % 11;
        }
        int check = (12 - p) % 11;
        return check == 10 ? 'X' : (char) ('0' + check);
    }
}
