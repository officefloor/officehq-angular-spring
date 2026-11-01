package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** A comment on a claim: its text, the user who wrote it and the sheet's today when it was written. */
@Embeddable
public class ClaimComment {

    @Column(name = "by_user", nullable = false, length = 100)
    private String by;

    @Column(name = "comment_date", nullable = false)
    private LocalDate date;

    @Column(name = "comment_text", nullable = false, length = 500)
    private String text;

    protected ClaimComment() {}

    public ClaimComment(String by, LocalDate date, String text) {
        this.by = by;
        this.date = date;
        this.text = text;
    }

    public String getBy() { return by; }

    public LocalDate getDate() { return date; }

    public String getText() { return text; }
}
