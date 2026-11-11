package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Another name a person goes by, unique among the names and aliases on the sheet. */
@Entity
@Table(name = "person_alias")
public class PersonAlias {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "person_id", nullable = false)
    private Long personId;

    @Column(name = "alias", nullable = false)
    private String alias;

    /** The alias as compared, as a name is: trimmed and lower-cased. */
    @Column(name = "alias_key", nullable = false)
    private String aliasKey;

    protected PersonAlias() {
    }

    public PersonAlias(String sheetId, Long personId, String alias) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.alias = alias.trim();
        this.aliasKey = Person.key(alias);
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public Long getPersonId() {
        return personId;
    }

    /** Move the alias to another person on the sheet. */
    public void setPersonId(Long personId) {
        this.personId = personId;
    }

    public String getAlias() {
        return alias;
    }

    public String getAliasKey() {
        return aliasKey;
    }
}
