package net.officefloor.hq.app.people;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A named group of people on one sheet (null for the unnamed sheet), numbered within it. Every person is in one group. */
@Entity
@Table(name = "person_group")
public class PersonGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The group's id within its sheet; the id the user and the API see. */
    @Column(name = "local_id")
    private Long localId;

    /** The id of the sheet the group belongs to; null for the unnamed sheet. */
    @Column(name = "sheet_id")
    private String sheetId;

    private String name;

    protected PersonGroup() {
    }

    public PersonGroup(String sheetId, long localId, String name) {
        this.sheetId = sheetId;
        this.localId = localId;
        this.name = name;
    }

    public Long getId() {
        return id;
    }

    public Long getLocalId() {
        return localId;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getName() {
        return name;
    }
}
