package net.officefloor.hq.app.claim;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;

/** One comment on a claim: the user who wrote it, the sheet's today then and its (trimmed) text. */
@Embeddable
public class ClaimComment {

    @Column(name = "by_user", nullable = false)
    private String by;

    @Column(name = "comment_date", nullable = false)
    private LocalDate date;

    @Column(name = "text", nullable = false)
    private String text;

    protected ClaimComment() {
    }

    public ClaimComment(String by, LocalDate date, String text) {
        this.by = by;
        this.date = date;
        this.text = text;
    }

    public String getBy() {
        return by;
    }

    public LocalDate getDate() {
        return date;
    }

    public String getText() {
        return text;
    }
}
