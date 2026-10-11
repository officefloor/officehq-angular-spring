package net.officefloor.hq.app.clients;

/**
 * A client's tax number: two letters then ten digits, spaces ignored, where the last two digits are
 * a check — 97 less the remainder of the first eight divided by 97 (a remainder of 0 gives 97).
 */
public final class TaxNumber {

    private TaxNumber() {
    }

    /** The number without its spaces; null when there is none. */
    public static String normalize(String number) {
        if (number == null) {
            return null;
        }
        String compact = number.replace(" ", "");
        return compact.isEmpty() ? null : compact;
    }

    /** Whether the number, spaces ignored, is in the form and passes its check. */
    public static boolean isValid(String number) {
        String compact = normalize(number);
        if (compact == null || !compact.matches("[A-Za-z]{2}[0-9]{10}")) {
            return false;
        }
        int remainder = Integer.parseInt(compact.substring(2, 10)) % 97;
        int check = remainder == 0 ? 97 : 97 - remainder;
        return check == Integer.parseInt(compact.substring(10));
    }
}
