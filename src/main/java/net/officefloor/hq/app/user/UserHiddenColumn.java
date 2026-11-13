package net.officefloor.hq.app.user;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;

/** A column of the people list a user has switched off on their screen, kept per user across every sheet. */
@Entity
@Table(name = "user_hidden_column")
public class UserHiddenColumn {

    /** The user and the column they switched off. */
    @Embeddable
    public record Key(@Column(name = "user_name", nullable = false) String userName,
            @Column(name = "column_key", nullable = false) String column) implements Serializable {
    }

    @EmbeddedId
    private Key key;

    protected UserHiddenColumn() {
    }

    public UserHiddenColumn(Key key) {
        this.key = key;
    }

    public Key getKey() {
        return key;
    }
}
