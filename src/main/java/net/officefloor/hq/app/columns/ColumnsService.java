package net.officefloor.hq.app.columns;

import java.util.Arrays;
import java.util.List;
import net.officefloor.hq.app.sheet.SheetNotFoundException;
import net.officefloor.hq.app.sheet.SheetRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The people-list columns each user has switched off on a sheet: only what the screen shows them.
 * Writes no audit. */
@Service
public class ColumnsService {

    /** The columns a user may switch off, in the order the list shows them. */
    public static final List<String> COLUMNS = List.of("terms", "contact", "aliases", "claimed", "due-total",
            "excess", "overdue-count", "overdue-total", "statement-count", "stated-total", "statement-date",
            "retired", "hold", "patrons", "backing");

    private final SheetRepository sheets;
    private final UserColumnsRepository columns;

    public ColumnsService(SheetRepository sheets, UserColumnsRepository columns) {
        this.sheets = sheets;
        this.columns = columns;
    }

    @Transactional(readOnly = true)
    public List<String> hiddenOf(String sheetId, String user) {
        return columns.findBySheetIdAndUser(sheetId, user).map(c -> split(c.getHidden())).orElse(List.of());
    }

    @Transactional
    public List<String> choose(String sheetId, String user, List<String> hidden) {
        List<String> trimmed = hidden.stream().map(String::trim).toList();
        if (!COLUMNS.containsAll(trimmed)) throw new ColumnUnknownException();
        if (!sheets.existsById(sheetId)) throw new SheetNotFoundException();
        List<String> ordered = COLUMNS.stream().filter(trimmed::contains).toList();
        String joined = String.join(",", ordered);
        columns.findBySheetIdAndUser(sheetId, user).ifPresentOrElse(c -> c.setHidden(joined),
                () -> columns.save(new UserColumns(sheetId, user, joined)));
        return ordered;
    }

    private static List<String> split(String hidden) {
        return hidden.isEmpty() ? List.of() : Arrays.asList(hidden.split(","));
    }
}
