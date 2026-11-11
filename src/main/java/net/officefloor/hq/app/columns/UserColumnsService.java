package net.officefloor.hq.app.columns;

import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the people-list columns each user has switched off. Choosing them writes no audit record. */
@Service
public class UserColumnsService {

    /** The people-list columns a user may switch off, in the order the list shows them. */
    public static final List<String> COLUMNS = List.of("terms", "contact", "aliases", "claimed", "due-total",
            "statement-count", "stated-total", "statement-date", "retired", "hold", "overdue-count", "overdue-total",
            "patrons", "backing", "excess");

    private final UserColumnsRepository columns;

    public UserColumnsService(UserColumnsRepository columns) {
        this.columns = columns;
    }

    /** The columns the user has switched off, in list order: none unless they chose some. */
    @Transactional(readOnly = true)
    public List<String> hiddenOf(String user) {
        return columns.findById(user).map(c -> split(c.getHidden())).orElse(List.of());
    }

    /** Save the columns the user has switched off; unknown keys are ignored. */
    @Transactional
    public List<String> choose(String user, List<String> hidden) {
        List<String> kept = COLUMNS.stream().filter(hidden::contains).toList();
        String joined = String.join(",", kept);
        UserColumns saved = columns.findById(user).orElseGet(() -> new UserColumns(user, joined));
        saved.setHidden(joined);
        return split(columns.save(saved).getHidden());
    }

    private static List<String> split(String joined) {
        return Arrays.stream(joined.split(",")).filter(COLUMNS::contains).toList();
    }
}
