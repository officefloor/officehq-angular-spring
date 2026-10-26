package net.officefloor.hq.app.person;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/** A grade is stored as its code. */
@Converter(autoApply = true)
public class GradeConverter implements AttributeConverter<Grade, String> {

    @Override
    public String convertToDatabaseColumn(Grade grade) {
        return grade == null ? null : grade.name();
    }

    @Override
    public Grade convertToEntityAttribute(String code) {
        return code == null ? null : Grade.valueOf(code);
    }
}
