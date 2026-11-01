package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** Another name a person goes by: kept without surrounding spaces, compared as names are (by its key). */
@Embeddable
public class PersonAlias {

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "alias", nullable = false)
    private String alias;

    @Column(name = "alias_key", nullable = false)
    private String aliasKey;

    protected PersonAlias() {}

    public PersonAlias(String sheetId, String alias) {
        this.sheetId = sheetId;
        this.alias = alias.strip();
        this.aliasKey = Person.keyOf(alias);
    }

    public String getAlias() { return alias; }

    public String getAliasKey() { return aliasKey; }
}
