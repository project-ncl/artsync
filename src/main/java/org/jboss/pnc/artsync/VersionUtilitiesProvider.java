package org.jboss.pnc.artsync;

import jakarta.enterprise.inject.Produces;
import org.jboss.da.common.version.VersionAnalyzer;
import org.jboss.da.common.version.VersionComparator;
import org.jboss.da.common.version.VersionParser;

import java.util.List;
import java.util.Set;

public class VersionUtilitiesProvider {

    public static final List<String> possibleVersionSuffixes = List.of(
        "redhat",
        "temporary-redhat",
        "managedsvc-redhat",
        "managedsvc-temporary-redhat",
        "alpha",
        "beta");

    @Produces
    VersionAnalyzer analyzer() {
        return new VersionAnalyzer(possibleVersionSuffixes);
    }

    @Produces
    VersionParser parser() {
        return new VersionParser(possibleVersionSuffixes);
    }

    @Produces
    VersionComparator comparator(VersionParser parser) {
        return new VersionComparator(parser);
    }

}
