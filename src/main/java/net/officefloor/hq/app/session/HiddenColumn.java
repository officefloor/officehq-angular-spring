package net.officefloor.hq.app.session;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;

/** A column of the people list a user has switched off on their screen. It is kept per user, across every sheet. */
@Entity
@Table(name = "user_hidden_column")
@IdClass(HiddenColumn.Key.class)
public class HiddenColumn {

    /** The columns of the people list a user may switch off. */
    public static final String KEYS = "claimed|due-total|excess|statement-count|stated-total|statement-date"
            + "|overdue-count|overdue-total|patrons|backing|hold|retired|terms|contact|aliases";

    public record Key(String user, String column) implements Serializable {
        public Key() {
            this(null, null);
        }
    }

    @Id
    @Column(name = "user_name", nullable = false)
    private String user;

    @Id
    @Column(name = "column_key", nullable = false)
    private String column;

    protected HiddenColumn() {
    }

    public HiddenColumn(String user, String column) {
        this.user = user;
        this.column = column;
    }

    public String getUser() {
        return user;
    }

    public String getColumn() {
        return column;
    }
}
