package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Locale;

/** Another name a person goes by on their sheet; it shares the names' uniqueness. */
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

    /** The alias lower-cased, as names are compared. */
    @Column(name = "alias_key", nullable = false)
    private String aliasKey;

    /** The sheet's today when the alias was added. */
    @Column(name = "made_on", updatable = false)
    private LocalDate madeOn;

    protected PersonAlias() {
    }

    public PersonAlias(String sheetId, Long personId, String alias, LocalDate madeOn) {
        this.sheetId = sheetId;
        this.personId = personId;
        this.alias = alias;
        this.aliasKey = alias.toLowerCase(Locale.ROOT);
        this.madeOn = madeOn;
    }

    /** Whether the alias was added at the end of a day: added while the sheet's today was on or
     *  before it. */
    public boolean madeAsOf(LocalDate day) {
        return madeOn == null || !madeOn.isAfter(day);
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

    public String getAlias() {
        return alias;
    }
}
