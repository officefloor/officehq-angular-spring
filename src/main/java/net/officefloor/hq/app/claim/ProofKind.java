package net.officefloor.hq.app.claim;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/** The kind of a claim's proof; a proof added with none chosen is a receipt. */
public enum ProofKind {
    RECEIPT, PHOTO;

    /** The kind as it is entered and shown: its name in lower case. */
    public String text() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind entered as the text given (any case, trimmed); null when it names none. */
    public static ProofKind of(String text) {
        if (text == null) return null;
        String t = text.trim();
        for (ProofKind kind : values()) {
            if (kind.text().equalsIgnoreCase(t)) return kind;
        }
        return null;
    }

    /** The kinds as a comma separated list, in their order. */
    public static String text(List<ProofKind> kinds) {
        return kinds.stream().map(ProofKind::text).collect(Collectors.joining(","));
    }
}
