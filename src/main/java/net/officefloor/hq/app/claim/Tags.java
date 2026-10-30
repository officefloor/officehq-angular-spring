package net.officefloor.hq.app.claim;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * A claim's tags: entered as comma separated text, each tag trimmed and lower-cased, repeats and blanks
 * dropped, kept sorted and comma separated ({@code ""} when none). Every tag ever used on a sheet is kept.
 */
@Service
public class Tags {

    /** The longest a single tag may be. */
    static final int TAG_MAX = 100;

    private final SheetTagRepository used;

    public Tags(SheetTagRepository used) {
        this.used = used;
    }

    /** The tags in the text (null: none), as stored: sorted and comma separated, {@code ""} when none. */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        List<String> tags = Arrays.stream(text.split(",")).map(t -> t.strip().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty()).distinct().sorted().toList();
        if (tags.stream().anyMatch(t -> t.length() > TAG_MAX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A tag is too long");
        }
        return String.join(",", tags);
    }

    /** The stored tags as a list, sorted. */
    public static List<String> list(String stored) {
        return stored == null || stored.isEmpty() ? List.of() : List.of(stored.split(","));
    }

    /** Keep the stored tags among the tags ever used on the sheet. */
    void use(String sheetId, String stored) {
        for (String tag : list(stored)) {
            if (!used.existsBySheetIdAndTag(sheetId, tag)) {
                used.saveAndFlush(new SheetTag(sheetId, tag));
            }
        }
    }

    /** Every tag ever used on the sheet, sorted. */
    public List<String> used(String sheetId) {
        return used.findBySheetIdOrderByTagAsc(sheetId).stream().map(SheetTag::getTag).toList();
    }
}
