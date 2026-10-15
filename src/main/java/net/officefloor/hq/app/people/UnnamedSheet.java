package net.officefloor.hq.app.people;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** The settings of the unnamed sheet (which has no id); there is at most one row, with id 1. */
@Entity
@Table(name = "unnamed_sheet")
public class UnnamedSheet {

    public static final int ID = 1;

    @Id
    private Integer id;

    private LocalDate anchor;

    protected UnnamedSheet() {
    }

    public LocalDate getAnchor() {
        return anchor;
    }
}
