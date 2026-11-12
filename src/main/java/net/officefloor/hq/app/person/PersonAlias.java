package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Another name a person goes by, stored trimmed; unique on the sheet among names and aliases,
 *  compared without regard to case. */
@Entity
@Table(name = "person_alias")
public class PersonAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "alias", nullable = false)
    private String alias;

    protected PersonAlias() {
    }

    PersonAlias(String sheetId, String alias) {
        this.sheetId = sheetId;
        this.alias = alias;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getAlias() {
        return alias;
    }
}
