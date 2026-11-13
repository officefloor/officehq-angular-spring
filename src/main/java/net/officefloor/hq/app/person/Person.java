package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDate;
import net.officefloor.hq.app.history.HistoryListener;

/** A person on a sheet, with a name; their grades, each dated, are kept as {@link PersonGrade}s. */
@Entity
@Table(name = "person")
@EntityListeners(HistoryListener.class)
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "name", nullable = false)
    private String name;

    /** Raised on every save, so a save from an editor that loaded an older version is refused. */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    /** The date the person is retired from; null while they are not retired. */
    @Column(name = "retired_from")
    private LocalDate retiredFrom;

    /** The person's own terms in days, which fix the due-by of their claims; null for none. */
    @Column(name = "terms")
    private Integer terms;

    /** The sheet's today when the person was added; null for one added before it was kept. */
    @Column(name = "added_on", updatable = false)
    private LocalDate addedOn;

    protected Person() {
    }

    public Person(String sheetId, String name, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.name = name;
        this.addedOn = addedOn;
    }

    /** The person as they stood at the end of a past day, with the name, own terms and retirement
     *  date they had then: a copy that is never saved. */
    public Person asItWas(String name, Integer terms, LocalDate retiredFrom) {
        Person was = new Person(sheetId, name, addedOn);
        was.id = id;
        was.version = version;
        was.terms = terms;
        was.retiredFrom = retiredFrom;
        return was;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public long getVersion() {
        return version;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDate getAddedOn() {
        return addedOn;
    }

    /** Whether the person was on the sheet at the end of a day: added while the sheet's today was on
     *  or before it (one added before that was kept always was). */
    public boolean onSheetAsOf(LocalDate day) {
        return addedOn == null || !addedOn.isAfter(day);
    }

    public LocalDate getRetiredFrom() {
        return retiredFrom;
    }

    public void setRetiredFrom(LocalDate retiredFrom) {
        this.retiredFrom = retiredFrom;
    }

    public Integer getTerms() {
        return terms;
    }

    public void setTerms(Integer terms) {
        this.terms = terms;
    }

    /** Whether the person is retired: any retirement date set counts, even one after the sheet's today. */
    public boolean isRetired() {
        return retiredFrom != null;
    }
}
