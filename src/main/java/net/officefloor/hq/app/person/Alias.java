package net.officefloor.hq.app.person;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/** An alias a person goes by: kept as names are kept, with the key it is compared by as names are. */
@Embeddable
public class Alias {

    @Column(name = "alias", nullable = false)
    private String alias;

    @Column(name = "alias_key", nullable = false)
    private String aliasKey;

    protected Alias() {
    }

    public Alias(String alias) {
        this.alias = Person.clean(alias);
        this.aliasKey = Person.keyOf(alias);
    }

    public String getAlias() {
        return alias;
    }

    public String getAliasKey() {
        return aliasKey;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Alias that && aliasKey.equals(that.aliasKey);
    }

    @Override
    public int hashCode() {
        return aliasKey.hashCode();
    }
}
