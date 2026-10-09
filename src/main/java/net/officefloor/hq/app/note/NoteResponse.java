package net.officefloor.hq.app.note;

import java.time.Instant;

public record NoteResponse(Long id, String targetType, Long targetId, String text, Instant at) {

    static NoteResponse from(Note note) {
        return new NoteResponse(note.getId(), note.getTargetType(), note.getTargetId(), note.getText(),
                note.getCreatedAt());
    }
}
