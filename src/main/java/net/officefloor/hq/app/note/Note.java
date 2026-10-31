package net.officefloor.hq.app.note;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;

/** A note on a sheet: its text, who added it and the sheet's today when it was added. */
@Entity
@Table(name = "sheet_note")
public class Note {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sheet_id", nullable = false)
    private String sheetId;

    @Column(name = "note_text", nullable = false, length = 1000)
    private String text;

    @Column(name = "added_by", nullable = false, length = 100)
    private String addedBy;

    @Column(name = "added_on", nullable = false)
    private LocalDate addedOn;

    protected Note() {
    }

    public Note(String sheetId, String text, String addedBy, LocalDate addedOn) {
        this.sheetId = sheetId;
        this.text = text;
        this.addedBy = addedBy;
        this.addedOn = addedOn;
    }

    public Long getId() {
        return id;
    }

    public String getSheetId() {
        return sheetId;
    }

    public String getText() {
        return text;
    }

    public String getAddedBy() {
        return addedBy;
    }

    public LocalDate getAddedOn() {
        return addedOn;
    }
}
