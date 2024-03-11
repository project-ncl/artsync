package org.jboss.pnc.artsync.model.hibernate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.bootstrap.resolver.maven.BootstrapMavenContext;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static io.quarkus.panache.common.Sort.Direction.Descending;
import static io.quarkus.panache.common.Sort.NullPrecedence.NULLS_LAST;

@Entity
@Table(name = "Job", indexes = {
    @Index(name = "idx_job_starttime", columnList = "startTime"),
    @Index(name = "idx_job_endtime", columnList = "endTime")
})
@Getter
@FilterDef(name = "Job.since",
    defaultCondition = "startTime >= :since",
    parameters = @ParamDef(name = "since", type = ZonedDateTime.class))
@Filter(name = "Job.since")
public class Job extends PanacheEntity {

    @Setter public ZonedDateTime startTime;
    @Setter public ZonedDateTime endTime;
    @Setter public ZonedDateTime lastProcessed;


    public long total = 0;
    public long filtered = 0;
    public long cached = 0;
    public long errors = 0;
    public long successes = 0;

    @OneToMany(mappedBy = "job", fetch = FetchType.EAGER)
    @JsonIgnoreProperties("job")
    Set<BuildStat> builds = new HashSet<>();

    //region Panache Queries
    /**
     * I want the one that was started last, but it must be finished.
     * @return
     */
    public static Optional<Job> getLastFinishedJob() {
        return find("from Job where lastProcessed is not null",
            Sort.by("startTime", Descending, NULLS_LAST)
                .and("endTime", Descending, NULLS_LAST))
            .firstResultOptional();
    }

    public static Paged<Job> getSince(Page page, ZonedDateTime since) {
        PanacheQuery<Job> query = find("from Job",
            Sort.by("startTime"));

        if (since != null) query.filter("Job.since", Parameters.with("since", since));

        return Paged.paginate(page, query);
    }

    public static Job getLatest() {
        return find("from Job", Sort.by("startTime", Descending)).firstResult();
    }


    //endregion
}
