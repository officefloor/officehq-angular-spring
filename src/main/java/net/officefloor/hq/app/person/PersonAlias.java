package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Another name a person is found by; unique on the sheet among names and aliases, as names are compared. */
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

    /** The alias as uniqueness compares it: trimmed and lower-cased. */
    @Column(name = "alias_key", nullable = false)
    private String aliasKey;

    protected PersonAlias() {
    }

    PersonAlias(String sheetId, String alias) {
        this.sheetId = sheetId;
        this.alias = alias.trim();
        this.aliasKey = Person.keyOf(alias);
    }

    /** The alias, without surrounding spaces. */
    public String getAlias() {
        return alias;
    }

    public String getAliasKey() {
        return aliasKey;
    }
}
