package org.jboss.pnc.artsync.config;

import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;
import org.jboss.pnc.dto.TargetRepository;

import java.nio.file.Path;
import java.util.Map;

//@ConfigMapping(prefix = "artsync.repositories")
public interface RepositoryMapping {
    @WithName("indy-aws-mappings")
    Map<String, String> indyAwsMappings();

    @WithDefault("false")
    // TODO
    Boolean generateGenericProxyRepositories();

    @WithDefault("~/.config/aws-repositories")
    Path settingsGenerationDirectory();

    @WithDefault("true")
    Boolean generateSettingsXml();

    default String mapToAws(TargetRepository pnc) {
        String indyRepo = parseIndyRepository(pnc);
        return indyAwsMappings().get(indyRepo);
    }

    static String parseIndyRepository(TargetRepository pnc) {
        String path = pnc.getRepositoryPath();
        String[] pathParts = path.split("/");

        return pathParts[pathParts.length - 1];
    }
}
