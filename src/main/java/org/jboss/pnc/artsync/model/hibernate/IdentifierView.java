package org.jboss.pnc.artsync.model.hibernate;

import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Getter;

@Getter
@RegisterForReflection
public class IdentifierView {
    public final String identifier;

    public IdentifierView(String identifier) {
        this.identifier = identifier;
    }
}
