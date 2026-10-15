package net.officefloor.hq.app.people;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A named sheet of people, identified by the id the user types to open it. Its "anchor" is the
 * reference date for everything date-dependent on it; absent until set. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    private LocalDate anchor;

    protected Sheet() {
    }

    public Sheet(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public LocalDate getAnchor() {
        return anchor;
    }
}
