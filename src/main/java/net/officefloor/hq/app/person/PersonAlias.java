package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/** Another name a person goes by: kept without the spaces around it, and compared as names are
 * (through its {@link Person#keyOf key}). */
@Embeddable
public class PersonAlias {

    @Column(name = "alias", nullable = false)
    private String alias;

    @Column(name = "alias_key", nullable = false)
    private String aliasKey;

    protected PersonAlias() {
    }

    public PersonAlias(String alias) {
        this.alias = alias.trim();
        this.aliasKey = Person.keyOf(alias);
    }

    public String getAlias() {
        return alias;
    }

    public String getAliasKey() {
        return aliasKey;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof PersonAlias other && Objects.equals(aliasKey, other.aliasKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(aliasKey);
    }
}
