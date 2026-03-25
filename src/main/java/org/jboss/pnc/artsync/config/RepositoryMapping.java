package org.jboss.pnc.artsync.config;

import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import org.jboss.pnc.dto.TargetRepository;
import org.jboss.pnc.enums.RepositoryType;

import java.nio.file.Path;
import java.util.Map;

//@ConfigMapping(prefix = "artsync.repositories")
public interface RepositoryMapping {
    @WithName("indy-aws-mappings")
    Map<String, String> indyAwsMappings();

    @WithDefault("~/.config/aws-repositories")
    Path settingsGenerationDirectory();

    @WithDefault("true")
    Boolean generateSettingsXml();

    @WithDefault("true")
    Boolean forceSingleGenericProxyRepository();

    String targetGenericProxyRepository();

    default String mapToAws(TargetRepository pnc) {
        if (forceSingleGenericProxyRepository() && pnc.getRepositoryType() == RepositoryType.GENERIC_PROXY) {
            if (targetGenericProxyRepository() == null || targetGenericProxyRepository().isBlank()) {
                throw new IllegalStateException("Target Generic Proxy Repository is null or empty but force is configured.");
            }

            return targetGenericProxyRepository();
        }

        String indyRepo = parseIndyRepository(pnc);
        return indyAwsMappings().get(indyRepo);
    }

    static String parseIndyRepository(TargetRepository pnc) {
        String path = pnc.getRepositoryPath();
        if (path.equals("/")) {
            return "/";
        }
        String[] pathParts = path.split("/");

        return pathParts[pathParts.length - 1];
    }
}
