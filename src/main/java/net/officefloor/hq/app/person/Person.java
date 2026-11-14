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
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import net.officefloor.hq.app.history.HistoryListener;
import org.hibernate.annotations.Generated;

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

    /** The person's contact, trimmed and lower-cased; null for none. */
    @Column(name = "contact")
    private String contact;

    /** The person's tags, lower case, sorted and comma separated; null when they have none. */
    @Column(name = "tags")
    private String tags;

    /** The sheet's today when the person was added; null for one added before it was kept. */
    @Column(name = "added_on", updatable = false)
    private LocalDate addedOn;

    /** The id of the person this one was merged into; null while they were not. A merged person has
     *  left the sheet. */
    @Column(name = "merged_into")
    private Long mergedInto;

    /** The sheet the person was transferred from; null for one not transferred in. */
    @Column(name = "origin_sheet_id")
    private String originSheetId;

    /** The reason a steward flagged the person; null while they were never flagged. */
    @Column(name = "flag_reason")
    private String flagReason;

    /** The last day the person's flag is in force; null while they were never flagged. */
    @Column(name = "flag_until")
    private LocalDate flagUntil;

    /** The person's place in the list, ascending; set by the store when they are added, so they go to the end. */
    @Generated
    @Column(name = "list_position", insertable = false, nullable = false)
    private Long position;

    protected Person() {
    }

    public Person(String sheetId, String name, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.name = name;
        this.addedOn = addedOn;
    }

    /** The person as they stood at the end of a past day, with the name, own terms, retirement
     *  date and contact they had then: a copy that is never saved. */
    public Person asItWas(String name, Integer terms, LocalDate retiredFrom, String contact) {
        Person was = new Person(sheetId, name, addedOn);
        was.id = id;
        was.version = version;
        was.position = position;
        was.terms = terms;
        was.retiredFrom = retiredFrom;
        was.contact = contact;
        was.tags = tags;
        was.originSheetId = originSheetId;
        was.flagReason = flagReason;
        was.flagUntil = flagUntil;
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

    public Long getPosition() {
        return position;
    }

    /** Swap the person's place in the list with another's. */
    public void swapPositionWith(Person other) {
        Long mine = position;
        position = other.position;
        other.position = mine;
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

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    /** The person's tags, sorted; empty when they have none. */
    public List<String> getTags() {
        return tags == null ? List.of() : List.of(tags.split(","));
    }

    /** Give the person their tags (already trimmed, lower case and without repeats), or none (empty). */
    public void setTags(Collection<String> tags) {
        this.tags = tags.isEmpty() ? null : String.join(",", new TreeSet<>(tags));
    }

    public String getOriginSheetId() {
        return originSheetId;
    }

    public Long getMergedInto() {
        return mergedInto;
    }

    /** Merge the person into another: they leave the sheet. */
    public void mergeInto(Long personId) {
        this.mergedInto = personId;
    }

    /** Flag the person with a reason until a date, replacing any flag they had. */
    public void flag(String reason, LocalDate until) {
        this.flagReason = reason;
        this.flagUntil = until;
    }

    /** Whether the person's flag is in force on a day: on or before its until-date. */
    public boolean flaggedOn(LocalDate day) {
        return flagReason != null && flagUntil != null && !day.isAfter(flagUntil);
    }

    /** The reason of the person's flag while it is in force on a day; null when it is not. */
    public String flagReasonOn(LocalDate day) {
        return flaggedOn(day) ? flagReason : null;
    }

    /** Whether the person is retired: any retirement date set counts, even one after the sheet's today. */
    public boolean isRetired() {
        return retiredFrom != null;
    }
}
