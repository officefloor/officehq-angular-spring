package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Another name a person goes by on a sheet, without surrounding spaces; unique on the sheet as names are. */
@Embeddable
public class PersonAlias {

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "alias", nullable = false)
    private String alias;

    protected PersonAlias() {
    }

    public PersonAlias(String sheetId, String alias) {
        this.sheetId = sheetId;
        this.alias = alias;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getAlias() {
        return alias;
    }
}
