package it.rebyte.catalog.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.data.annotation.PersistenceCreator;
import it.rebyte.commons.license.LicenseKeyPattern;
import it.rebyte.catalog.service.VersionNumbers;

public class SoftwareListing {
    private final Set<Platform> platforms;
    private final List<Version> versions;
    private final String licenceKeyPattern;

    protected SoftwareListing() {
        this.platforms = Set.of();
        this.versions = List.of();
        this.licenceKeyPattern = null;
    }

    @PersistenceCreator
    public SoftwareListing(Set<Platform> platforms, List<Version> versions, String licenceKeyPattern) {
        this.platforms = Set.copyOf(platforms);
        this.versions = List.copyOf(versions);
        this.licenceKeyPattern = licenceKeyPattern;
    }

    public Set<Platform> getPlatforms() { return platforms; }
    public List<Version> getVersions() { return versions; }
    public String getLicenceKeyPattern() { return licenceKeyPattern; }
    public String effectiveLicenceKeyPattern() {
        return licenceKeyPattern == null ? LicenseKeyPattern.DEFAULT : licenceKeyPattern;
    }

    public Version currentVersion() {
        return versions.stream().max(Comparator.comparing(Version::getNumber, VersionNumbers.comparator())).orElseThrow();
    }
}
