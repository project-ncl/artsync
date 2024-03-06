package org.jboss.pnc.artsync.model.hibernate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.FilterDefs;
import org.hibernate.annotations.FilterJoinTable;
import org.hibernate.annotations.Filters;
import org.hibernate.annotations.ParamDef;
import org.hibernate.proxy.HibernateProxy;
import org.jboss.pnc.artsync.model.Asset;
import org.jboss.pnc.artsync.model.AssetUpload;
import org.jboss.pnc.artsync.model.UploadResult;
import org.jboss.pnc.artsync.model.UploadResult.Error.AWSError;
import org.jboss.pnc.artsync.model.UploadResult.Error.GenericError;
import org.jboss.pnc.enums.RepositoryType;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.jboss.pnc.artsync.config.ArtifactConfig.SourceFilter.toStoreKey;
import static org.jboss.pnc.artsync.config.RepositoryMapping.parseIndyRepository;

@Entity
@Table(name = "AssetEntry", indexes = {
    @Index(name = "idx_assetentry_category", columnList = "errorCategory"),
    @Index(name = "idx_assetentry_type", columnList = "type"),
    @Index(name = "idx_assetentry_identifier", columnList = "identifier"),
    @Index(name = "idx_assetentry_createdtime", columnList = "createdTime"),
    @Index(name = "idx_assetentry_error", columnList = "error"),
    @Index(name = "idx_assetentry_fq_buildstat", columnList = "processingBuild_id")
})
@FilterDefs({
    @FilterDef(name = "Ass.likeIdentifier",
        defaultCondition = "identifier like :identifier",
        parameters = @ParamDef(name = "identifier", type = String.class)),
    @FilterDef(name = "Ass.eqIdentifier",
        defaultCondition = "identifier = :identifier",
        parameters = @ParamDef(name = "identifier", type = String.class)),
    @FilterDef(name = "Ass.eqType",
        defaultCondition = "type = :type",
        parameters = @ParamDef(name = "type", type = String.class)),
    @FilterDef(name = "Ass.hasErrors",
        defaultCondition = "error is not NULL"),
    @FilterDef(name = "Ass.sinceCreated",
        defaultCondition = "createdTime >= :since",
        parameters = @ParamDef(name = "since", type = ZonedDateTime.class)),
    @FilterDef(name = "Ass.eqCategory",
        defaultCondition = "errorCategory = :category",
        parameters = @ParamDef(name = "category", type = String.class)),
    @FilterDef(name = "Ass.likeErrorType",
        defaultCondition = "error like :errorType",
        parameters = @ParamDef(name = "errorType", type = String.class)),
    @FilterDef(name = "Ass.eqErrorType",
        defaultCondition = "error = :errorType",
        parameters = @ParamDef(name = "errorType", type = String.class)),
    @FilterDef(name = "Ass.eqBuildStat",
        defaultCondition = "processingBuild_id = :statId",
        parameters = @ParamDef(name = "statId", type = Long.class))
})
@Filters({
    @Filter(name = "Ass.likeIdentifier"),
    @Filter(name = "Ass.eqIdentifier"),
    @Filter(name = "Ass.eqType"),
    @Filter(name = "Ass.hasErrors"),
    @Filter(name = "Ass.sinceCreated"),
    @Filter(name = "Ass.eqCategory"),
    @Filter(name = "Ass.likeErrorType"),
    @Filter(name = "Ass.eqErrorType"),
    @Filter(name = "Ass.eqBuildStat"),
})
public class AssetEntry extends PanacheEntity {

    //region Properties
    @Getter
    @Column(nullable = false)
    public String identifier;
    public String artifactID;
    @Enumerated(EnumType.STRING)
    public RepositoryType type;
    public String filename;
    public long size;
    public String md5;
    public String sha1;
    public String sha256;
    public String originBuildID;

    @ManyToOne
    @JsonIgnoreProperties("job")
    @Filter(name = "Ass.eqBuildId", condition = "buildID = :buildId")
    public BuildStat processingBuild;
    public String indyRepository;
    public String awsRepository;

    @Column(columnDefinition = "TEXT")
    public String sourceUrl;

    @Column(columnDefinition = "TEXT")
    public String deployedUrl;

    public ZonedDateTime createdTime;
    public ZonedDateTime uploadTime;

    // ERROR fields
    public String error;

    @Column(columnDefinition = "TEXT")
    public String errorDetail;

    @Enumerated(EnumType.STRING)
    public Category errorCategory;
    public boolean consistentChecksums = false;
    //endregion

    //region Constructors
    public AssetEntry() {
    }

    public AssetEntry(AssetUpload<? extends Asset> uploadResult) {
        init(uploadResult.asset());
        this.awsRepository = uploadResult.awsRepository();
        this.deployedUrl = uploadResult.deployedUrl();
        this.createdTime = uploadResult.uploadTime();
        this.uploadTime = uploadResult.uploadTime();
    }

    private void init(Asset asset) {
        this.identifier = asset.getIdentifier();
        this.filename = asset.getFilename();
        this.size = asset.getSize();
        this.md5 = asset.getMd5();
        this.sha1 = asset.getSha1();
        this.sha256 = asset.getSha256();
        this.originBuildID = asset.getOriginBuildID();
        this.processingBuild = asset.getProcessingBuildID();
        this.artifactID = asset.getArtifactID();
        this.type = asset.getType();
        this.indyRepository = toStoreKey(asset.getSourceRepository())
            .orElse(parseIndyRepository(asset.getSourceRepository()));
        this.sourceUrl = asset.getDownloadURI().toString();
    }

    public AssetEntry(UploadResult.Error<? extends Asset> error) {
        init(error.context());
        this.error = error.niceClassName();
        this.createdTime = ZonedDateTime.now();
        this.errorCategory = error.category();
        switch (error) {
            case AWSError.Conflict<? extends Asset>(Asset ign,
                                                    String deployUrl,
                                                    String awsRepo,
                                                    java.time.ZonedDateTime upload)
                -> {
                this.awsRepository = awsRepo;
                this.deployedUrl = deployUrl;
                this.uploadTime = upload;
            }
            case GenericError.Invalidated<? extends Asset>(Asset ign,
                                                           String deployUrl,
                                                           String awsRepo,
                                                           java.time.ZonedDateTime upload)
                -> {
                this.awsRepository = awsRepo;
                this.deployedUrl = deployUrl;
                this.uploadTime = upload;
            }

            case GenericError.CorruptedData<? extends Asset>(Asset ign, String lines) -> this.errorDetail = lines;
            case AWSError.ServerError<? extends Asset>(Asset ign, String lines) -> this.errorDetail = lines;
            case GenericError.MissingUpload<? extends Asset>(Asset ign, String lines) -> this.errorDetail = lines;
            case GenericError.UnknownError<? extends Asset> (Asset ign, String lines) -> this.errorDetail = lines;

            case GenericError.MissingRepositoryMapping<? extends Asset>(Asset ign, String missingRepository)
                -> this.errorDetail = missingRepository;
            case GenericError.UncaughtException<? extends Asset>(Asset ign, Throwable thr)
                -> this.errorDetail = thr.toString();
            case AWSError.ConnectionError<? extends Asset>(Asset ign, String detail)
                -> this.errorDetail = detail;

            // no details
            case AWSError.InvalidToken<? extends Asset> v -> {}
            case AWSError.QuotaExceeded<? extends Asset> v -> {}
            case AWSError.RateLimitExceeded<? extends Asset> v -> {}
            case GenericError.Skipped<? extends Asset> v -> {}
            case GenericError.Timeout<? extends Asset> v -> {}
            case UploadResult.Error.IndyError<? extends Asset> v -> {}
        };
    }
    //endregion

    //region Panache Queries
    public static long countSuccessfulIdentifiers() {
        return count("errorCategory = ?1 or errorCategory is null", Category.IGNORE);
    }

    public static PanacheQuery<IdentifierView> getSuccesses() {
        return find("errorCategory = ?1 or errorCategory is null", Sort.by("id"), Category.IGNORE)
            .project(IdentifierView.class);
    }

    public static Paged<AssetEntry> getAllFiltered(Page page,
                                                   String identifier,
                                                   RepositoryType type,
                                                   boolean errors,
                                                   ZonedDateTime since,
                                                   Category category,
                                                   String errorType,
                                                   Long buildStatId,
                                                   String pncProcessingBuildID,
                                                   Long jobId) {
        String queryString = "from AssetEntry ae";
        Parameters params = new Parameters();

        // @Filter doesn't work on ManyToOne associations...
        if (pncProcessingBuildID != null || jobId != null) {
            queryString += " inner join ae.processingBuild pb";
            List<String> killMe = new ArrayList<>();
            if (pncProcessingBuildID != null) {
                killMe.add("pb.buildID = :buildId");
                params.and("buildId", pncProcessingBuildID);
            }
            if (jobId != null) {
                killMe.add("pb.job.id = :jobId");
                params.and("jobId", jobId);
            }
            queryString += " where " + String.join(" and ", killMe);
        }

        PanacheQuery<AssetEntry> query = find(queryString, Sort.by("createdTime"), params);
        if (identifier != null) {
            if (identifier.contains("%") || identifier.contains("_"))
                query.filter("Ass.likeIdentifier", Parameters.with("identifier", identifier));
            else
                query.filter("Ass.eqIdentifier", Parameters.with("identifier", identifier));
        }
        if (type != null) query.filter("Ass.eqType", Parameters.with("type", type.name()));
        if (errors) query.filter("Ass.hasErrors");
        if (since != null) query.filter("Ass.sinceCreated", Parameters.with("since", since));
        if (category != null) query.filter("Ass.eqCategory", Parameters.with("category", category.name()));
        if (errorType != null) {
            if (errorType.contains("%") || errorType.contains("_"))
                query.filter("Ass.likeErrorType", Parameters.with("errorType", errorType));
            else
                query.filter("Ass.eqErrorType", Parameters.with("errorType", errorType));
        }
        if (buildStatId != null) query.filter("Ass.eqBuildStat", Parameters.with("statId", buildStatId));

        return Paged.paginate(page, query);
    }

    public static boolean markFixed(long id) {
        return update("errorCategory = :category where id = :id",
            Parameters.with("category", Category.IGNORE)
                .and("id", id)) > 0;
    }
    //endregion

    @Override
    public final boolean equals(Object o) {
        if (this == o) return true;
        if (o == null) return false;
        Class<?> oEffectiveClass = o instanceof HibernateProxy ? ((HibernateProxy) o).getHibernateLazyInitializer().getPersistentClass() : o.getClass();
        Class<?> thisEffectiveClass = this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass() : this.getClass();
        if (thisEffectiveClass != oEffectiveClass) return false;
        AssetEntry entry = (AssetEntry) o;
        return id != null && Objects.equals(id, entry.id);
    }

    @Override
    public final int hashCode() {
        return this instanceof HibernateProxy ? ((HibernateProxy) this).getHibernateLazyInitializer().getPersistentClass().hashCode() : getClass().hashCode();
    }
}
