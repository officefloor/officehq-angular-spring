package net.officefloor.hq.app.claim;

import java.util.Arrays;
import java.util.Locale;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** A claim's tags as stored: each tag of a comma separated text trimmed and lower-cased, blanks and
 *  repeats dropped, sorted and joined by commas. */
public final class Tags {

    private Tags() {
    }

    /** The tags of the text as stored; null when it names none. */
    public static String normalise(String text) {
        if (text == null) {
            return null;
        }
        String joined = Arrays.stream(text.split(",")).map(t -> t.trim().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty()).collect(Collectors.toCollection(TreeSet::new)).stream()
                .collect(Collectors.joining(","));
        return joined.isEmpty() ? null : joined;
    }

    /** The tags stored, one by one. */
    public static String[] of(String tags) {
        return tags == null ? new String[0] : tags.split(",");
    }
}
