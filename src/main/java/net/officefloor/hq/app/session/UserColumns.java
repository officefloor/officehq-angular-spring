package net.officefloor.hq.app.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.Arrays;
import java.util.List;

/** The people-list columns a user has switched off on their screen. A user without a line sees them all. */
@Entity
@Table(name = "user_columns")
public class UserColumns {

    /** The people-list columns a user may switch off. */
    public static final List<String> COLUMNS = List.of(
            "claimed", "due-total", "statement-count", "stated-total", "statement-date", "overdue-count",
            "overdue-total", "patrons", "backing", "hold", "retired", "terms", "contact", "aliases", "excess");

    @Id
    @Column(name = "user_name", nullable = false, length = 100)
    private String userName;

    @Column(name = "hidden", nullable = false, length = 500)
    private String hidden;

    protected UserColumns() {
    }

    public UserColumns(String userName) {
        this.userName = userName;
        this.hidden = "";
    }

    public String getUserName() {
        return userName;
    }

    public List<String> getHidden() {
        return hidden.isEmpty() ? List.of() : Arrays.asList(hidden.split(","));
    }

    /** Keeps the known columns only, in their list order, each once. */
    public void setHidden(List<String> columns) {
        this.hidden = String.join(",", COLUMNS.stream().filter(columns::contains).toList());
    }
}
