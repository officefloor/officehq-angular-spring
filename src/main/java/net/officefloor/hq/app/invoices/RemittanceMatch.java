package net.officefloor.hq.app.invoices;

import java.util.Locale;

/** How alike a payment's remittance text is to an invoice's purchase order number. */
final class RemittanceMatch {

    /** The least similarity that counts as a match. */
    static final double THRESHOLD = 0.85;

    private RemittanceMatch() {
    }

    /**
     * The Jaro-Winkler similarity of the two strings, lower-cased: characters match within a window of
     * half the longer length less one, transpositions are halved, and a common prefix of up to four
     * characters is boosted by 0.1.
     */
    static double similarity(String first, String second) {
        String a = first.toLowerCase(Locale.ROOT);
        String b = second.toLowerCase(Locale.ROOT);
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b) ? 1 : 0;
        }
        int window = Math.max(0, Math.max(a.length(), b.length()) / 2 - 1);
        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int from = Math.max(0, i - window);
            int to = Math.min(b.length() - 1, i + window);
            for (int j = from; j <= to; j++) {
                if (!bMatched[j] && a.charAt(i) == b.charAt(j)) {
                    aMatched[i] = true;
                    bMatched[j] = true;
                    matches++;
                    break;
                }
            }
        }
        if (matches == 0) {
            return 0;
        }
        int outOfOrder = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (aMatched[i]) {
                while (!bMatched[k]) {
                    k++;
                }
                if (a.charAt(i) != b.charAt(k)) {
                    outOfOrder++;
                }
                k++;
            }
        }
        double m = matches;
        double t = outOfOrder / 2.0;
        double jaro = (m / a.length() + m / b.length() + (m - t) / m) / 3;
        int prefix = 0;
        while (prefix < 4 && prefix < a.length() && prefix < b.length() && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
