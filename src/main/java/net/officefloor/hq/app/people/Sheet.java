package net.officefloor.hq.app.people;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A named sheet of people, identified by the id the user types to open it. */
@Entity
@Table(name = "sheet")
public class Sheet {

    @Id
    private String id;

    protected Sheet() {
    }

    public Sheet(String id) {
        this.id = id;
    }

    public String getId() {
        return id;
    }
}
