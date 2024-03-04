package org.jboss.pnc.artsync.config;

import io.smallrye.config.WithParentName;
import org.commonjava.indy.folo.dto.TrackedContentEntryDTO;
import org.commonjava.indy.model.core.GenericPackageTypeDescriptor;
import org.commonjava.indy.pkg.maven.model.MavenPackageTypeDescriptor;
import org.commonjava.indy.pkg.npm.model.NPMPackageTypeDescriptor;
import org.jboss.pnc.dto.TargetRepository;
import org.jboss.pnc.enums.RepositoryType;
import org.jboss.pnc.dto.Artifact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//@ConfigMapping(prefix = "artsync.artifacts")
public interface ArtifactConfig {
    TypeFilter allowedTypes();

    UploadFilter uploadFilter();

    SourceFilter sourceFilter();

    interface TypeFilter {

        @WithParentName
        Set<RepositoryType> allowedTypes();

        default boolean denies(Artifact artifact) {
            return denies(artifact, new HashSet<>());
        }

        default boolean denies(TrackedContentEntryDTO dto) {
            return denies(dto, new HashSet<>());
        }

        default boolean denies(Artifact artifact, Set<RepositoryType> used) {
            return denies(artifact.getTargetRepository().getRepositoryType(), used);
        }

        default boolean denies(TrackedContentEntryDTO dto, Set<RepositoryType> used) {
            return denies(mapIndyPkgKey(dto.getStoreKey().getPackageType()), used);
        }

        private boolean denies(RepositoryType type, Set<RepositoryType> used) {
            if (!allowedTypes().contains(type)) {
                used.add(type);
                return true;
            }
            return false;
        }
    }

    interface UploadFilter {
        @WithParentName
        Map<RepositoryType, Set<Pattern>> filters();

        default boolean denies(RepositoryType repositoryType, String path, Set<Pattern> used) {
            return filters().getOrDefault(repositoryType, Set.of()).stream().anyMatch(pattern -> appendIfUsed(pattern, path, used));
        }

        default boolean denies(TrackedContentEntryDTO dto) {
            return denies(dto, new HashSet<>());
        }

        default boolean denies(TrackedContentEntryDTO dto, Set<Pattern> used) {
            String packageType = dto.getStoreKey().getPackageType();

            return denies(mapIndyPkgKey(packageType), dto.getPath(), used);
        }

        default boolean denies(Artifact dto) {
            return denies(dto, new HashSet<>());
        }

        default boolean denies(Artifact dto, Set<Pattern> used) {
            return denies(dto.getTargetRepository().getRepositoryType(), dto.getDeployPath(), used);
        }
    }

    private static boolean appendIfUsed(Pattern pattern, String string, Set<Pattern> used) {
        if (pattern.asMatchPredicate().test(string)){
            used.add(pattern);
            return true;
        }
        return false;
    }

    private static RepositoryType mapIndyPkgKey(String packageType) {
        return switch (packageType) {
            case MavenPackageTypeDescriptor.MAVEN_PKG_KEY -> RepositoryType.MAVEN;
            case GenericPackageTypeDescriptor.GENERIC_PKG_KEY -> RepositoryType.GENERIC_PROXY;
            case NPMPackageTypeDescriptor.NPM_PKG_KEY -> RepositoryType.NPM;
            case null, default -> throw new IllegalArgumentException("Unknown package type " + packageType);
        };
    }

    interface SourceFilter {
        Logger LOG = LoggerFactory.getLogger(SourceFilter.class);
        String PATH_STOREKEY_STRING_PATTERN = "/api/content/(.+?)/(.+?)/(.+?)/?$";
        Pattern PATH_STOREKEY_PATTERN = Pattern.compile(PATH_STOREKEY_STRING_PATTERN);

        @WithParentName
        Set<Pattern> filters();

        default boolean denies(TrackedContentEntryDTO dto) {
            return denies(dto, new HashSet<>());
        }

        default boolean denies(TrackedContentEntryDTO dto, Set<Pattern> used) {
            return filters().stream().anyMatch(pattern -> appendIfUsed(pattern, dto.getStoreKey().toString(), used));
        }

        default boolean denies(Artifact dto) {
            return denies(dto, new HashSet<>());
        }

        default boolean denies(Artifact dto, Set<Pattern> used) {
            Optional<String> storeKey = toStoreKey(dto.getTargetRepository());
            if (storeKey.isPresent()) {
                boolean match = filters().stream().anyMatch(pattern -> appendIfUsed(pattern, storeKey.get(), used));
//                if (match) {
//                    LOG.trace("Filtered out: {} \n {}", storeKey, dto);
//                }
                return match;
            }
            // FIXME maybe return 'true'? due to irregular repo path
            // or it may be a DISTRIBUTION_ARCHIVE (which it should never be)
            return false;
        }

        static Optional<String> toStoreKey(TargetRepository source) {
            Matcher matcher = PATH_STOREKEY_PATTERN.matcher(source.getRepositoryPath());
            if (!matcher.matches()) {
                return Optional.empty();
            }

            return Optional.of(MessageFormat.format("{0}:{1}:{2}",
                matcher.group(1),
                matcher.group(2),
                matcher.group(3)));

        }
    }
}
