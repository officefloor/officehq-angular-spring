package net.officefloor.hq.app.user;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps which columns of the people list each user's screen shows. Switching one writes no audit record. */
@Service
public class UserColumnsService {

    /** The columns of the people list a user may switch off; every one is shown until they do. */
    public static final List<String> COLUMNS = List.of("claimed", "due-total", "excess", "statement-count",
            "stated-total", "statement-date", "overdue-count", "overdue-total", "patrons", "backing", "hold",
            "retired", "terms", "contact", "aliases");

    private final UserHiddenColumnRepository hidden;

    public UserColumnsService(UserHiddenColumnRepository hidden) {
        this.hidden = hidden;
    }

    /** The columns the user switched off, in the order of {@link #COLUMNS}. */
    @Transactional(readOnly = true)
    public List<String> hidden(String user) {
        List<String> off = hidden.findByKeyUserName(user).stream().map(c -> c.getKey().column()).toList();
        return COLUMNS.stream().filter(off::contains).toList();
    }

    /** Switch one column on or off for the user; one not in {@link #COLUMNS} is refused. */
    @Transactional
    public List<String> show(String user, String column, boolean shown) {
        if (!COLUMNS.contains(column)) {
            throw new ColumnRefusedException("Choose a column: " + String.join(", ", COLUMNS) + ".");
        }
        UserHiddenColumn.Key key = new UserHiddenColumn.Key(user, column);
        if (shown) {
            hidden.deleteById(key);
        } else if (!hidden.existsById(key)) {
            hidden.save(new UserHiddenColumn(key));
        }
        return hidden(user);
    }
}
