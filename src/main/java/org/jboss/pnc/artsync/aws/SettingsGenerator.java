package org.jboss.pnc.artsync.aws;

import io.quarkus.runtime.StartupEvent;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.FileProps;
import io.vertx.core.file.FileSystem;
import io.vertx.core.file.FileSystemException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import lombok.extern.slf4j.Slf4j;
import org.jboss.pnc.artsync.config.ArtsyncConfig;
import org.jboss.pnc.artsync.config.RepositoryMapping;
import org.jboss.pnc.artsync.model.UploadResult;
import org.jboss.pnc.artsync.pnc.Result;
import software.amazon.awssdk.services.codeartifact.model.PackageFormat;

import java.io.File;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static software.amazon.awssdk.services.codeartifact.model.PackageFormat.MAVEN;
import static software.amazon.awssdk.services.codeartifact.model.PackageFormat.NPM;

@ApplicationScoped
@Slf4j
public class SettingsGenerator {

    private static final String mvnBaseTemplate =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <settings xmlns="http://maven.apache.org/settings/1.0.0"
              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
              xsi:schemaLocation="http://maven.apache.org/SETTINGS/1.0.0 http://maven.apache.org/xsd/settings-1.0.0.xsd">
              <profiles>
                <profile>
                  <id>aws</id>
                  <repositories>
                    <repository>
                      <id>{0}</id>
                      <url>{1}</url>
                    </repository>
                  </repositories>
                </profile>
              </profiles>
              <servers>
                <server>
                  <id>{0}</id>
                  <username>aws</username>
                  <password>{2}</password>
                </server>
              </servers>
            </settings>""";

    private static final String npmBaseTemplate = """
        fetch-retries=10
        registry={0}
        //{1}:_authToken={2}
        """;
    public static final String AUTH_TOKEN_KEY = "CODEARTIFACT_AUTH_TOKEN";
    public static final String MVN_AUTH_TOKEN_ENV = "${env." + AUTH_TOKEN_KEY + "}";
    public static final String AUTH_TOKEN_ENV = "${" + AUTH_TOKEN_KEY + "}";

    private final RepositoryMapping repositoryConfig;

    private final AWSService aws;

    private final FileSystem filesystem;

    private final Map<String,Map<PackageFormat, File>> repoIdToSettingsPath;

    private final Map<String,Map<PackageFormat, String>> repoIdToRepoUrl;

    public SettingsGenerator(ArtsyncConfig config,
                             AWSService aws,
                             Vertx vertx) {
        this.repositoryConfig = config.repositoryMapping();
        this.aws = aws;
        this.filesystem = vertx.fileSystem();
        this.repoIdToRepoUrl = new HashMap<>();
        this.repoIdToSettingsPath = new HashMap<>();
    }

    void startup(@Observes StartupEvent start) {
        if (!repositoryConfig.generateSettingsXml()) {
            log.info("Using user-native Maven settings.");
            return;
        }

        validateRepositoriesInConfig();

        Path baseDir = createDirectory(repositoryConfig.settingsGenerationDirectory());

        repositoryConfig.indyAwsMappings().values().forEach(awsrepo -> {
            Result<String> mvn = aws.getRepositoryEndpoint(awsrepo, MAVEN);
            Result<String> npm = aws.getRepositoryEndpoint(awsrepo, NPM);
            Path dir = createDirectory(baseDir.resolve(awsrepo));
            switch (mvn) {
                case Result.Success(var repoUrl) -> {
                    this.repoIdToRepoUrl.putIfAbsent(awsrepo, new HashMap<>());
                    this.repoIdToRepoUrl.get(awsrepo).put(MAVEN, repoUrl);
                    this.repoIdToSettingsPath.putIfAbsent(awsrepo, new HashMap<>());
                    this.repoIdToSettingsPath.get(awsrepo).put(MAVEN, createSettingsXML(dir, awsrepo, repoUrl));
                    log.info("Settings.xml for {} generated at {}", awsrepo, repoIdToSettingsPath.get(awsrepo).get(MAVEN));
                }
                case Result.Error err -> {
                    log.error("Cannot resolve '{}' repository endpoint. Error: {}", awsrepo, err);
                    throw new IllegalStateException("Cannot resolve '"+ awsrepo +"' repository endpoint.");
                }
            };
            switch (npm) {
                case Result.Success(var repoUrl) -> {
                    this.repoIdToRepoUrl.putIfAbsent(awsrepo, new HashMap<>());
                    this.repoIdToRepoUrl.get(awsrepo).put(NPM, repoUrl);
                    this.repoIdToSettingsPath.putIfAbsent(awsrepo, new HashMap<>());
                    this.repoIdToSettingsPath.get(awsrepo).put(NPM, createNPMRC(dir, repoUrl));
                    log.info(".npmrc for {} generated at {}", awsrepo, repoIdToSettingsPath.get(awsrepo).get(NPM));
                }
                case Result.Error err -> {
                    log.error("Cannot resolve '{}' repository endpoint. Error: {}", awsrepo, err);
                    throw new IllegalStateException("Cannot resolve '"+ awsrepo +"' repository endpoint.");
                }
            };
        });
    }

    public File getSettings(String awsrepo, PackageFormat format) {
        return repoIdToSettingsPath.get(awsrepo).get(format);
    }

    public String getRepoUrl(String awsrepo, PackageFormat format) {
        return repoIdToRepoUrl.get(awsrepo).get(format);
    }

    private void validateRepositoriesInConfig() {
        Collection<String> awsRepositories = repositoryConfig.indyAwsMappings().values();
        log.info("Validating repositories {} are present in AWS.", awsRepositories) ;
        Result<Boolean> result = aws.validateRepositories(awsRepositories);
        switch (result) {
            case Result.Error err -> throw new IllegalStateException("Error communicating with AWS. Error: " + err);
            case Result.Success(var present) -> {
                if (present) {
                    log.info("Validation succeeded.");
                } else {
                    log.error("Check repositories in AWS. They were not found.");
                    throw new IllegalStateException("Repositories missing in AWS.");
                }
            }
        }
    }

    private Path createDirectory(Path path) {
        String dirPath = path.toAbsolutePath().toString();

        try {
            FileProps props = filesystem.propsBlocking(dirPath);
            if (props != null && !props.isDirectory()) {
                throw new IllegalArgumentException(path.toString() + " is not a directory.");
            }
        } catch (FileSystemException ex) {
            if (ex.getCause() instanceof NoSuchFileException) {
                filesystem.mkdirs(dirPath);
            } else {
                throw new IllegalStateException("Encountered filesystem exception.", ex);
            }
        }

        return path;
    }

    private File createSettingsXML(Path directory, String repositoryId, String repositoryURL) {
        Path settings = directory.resolve("settings.xml");
        filesystem.createFile(settings.toString());
        filesystem.writeFileBlocking(settings.toString(),
            Buffer.buffer(generateSettingsXML(repositoryId,
                repositoryURL,
                MVN_AUTH_TOKEN_ENV)));

        return settings.toFile();
    }

    private static String generateSettingsXML(String repositoryId, String repositoryURL, String repositoryPassword) {
        return MessageFormat.format(mvnBaseTemplate, repositoryId, repositoryURL, repositoryPassword);
    };

    private File createNPMRC(Path directory, String repositoryURL) {
        Path npmrc = directory.resolve(".npmrc");
        filesystem.createFile(npmrc.toString());
        filesystem.writeFileBlocking(npmrc.toString(),
            Buffer.buffer(generateNPMRC(repositoryURL, AUTH_TOKEN_ENV)));
        return npmrc.toFile();
    }

    private static String generateNPMRC(String repositoryURL, String repositoryPassword) {
        String noProtocol = repositoryURL.replaceFirst("http://", "").replaceFirst("https://","");

        return MessageFormat.format(npmBaseTemplate, repositoryURL, noProtocol, repositoryPassword);
    };

    public static void main(String[] args) {
        String repositoryURL = "https://test-domain.codeartifact.amazonaws.com/maven/test-repository/";
        System.out.println(generateSettingsXML("pnc-builds-test-repository",
            repositoryURL,
            "repoPassword"));
        String noProtocol = repositoryURL.replaceFirst("http://", "").replaceFirst("https://","");
        System.out.println(noProtocol);
        System.out.println(new UploadResult.Error.AWSError.ServerError("srt", "set").niceClassName());
    }
}
