package net.officefloor.hq.app.note;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A free-text note written against a record, such as a project or an invoice. */
@Entity
@Table(name = "note")
public class Note {

    /** Target type for notes kept on a project. */
    public static final String PROJECT = "project";

    /** Target type for notes kept on an invoice. */
    public static final String INVOICE = "invoice";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_type", nullable = false)
    private String targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    private String text;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Note() {
    }

    public Note(String targetType, Long targetId, String text, Instant createdAt) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.text = text;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getTargetType() {
        return targetType;
    }

    public Long getTargetId() {
        return targetId;
    }

    public String getText() {
        return text;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
