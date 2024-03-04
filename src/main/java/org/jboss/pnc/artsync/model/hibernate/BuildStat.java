package org.jboss.pnc.artsync.model.hibernate;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.quarkus.hibernate.orm.panache.PanacheEntity;
import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import io.quarkus.hibernate.orm.panache.PanacheQuery;
import io.quarkus.panache.common.Page;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.FilterDefs;
import org.hibernate.annotations.ParamDef;

import java.time.ZonedDateTime;

import static org.jboss.pnc.artsync.model.hibernate.Paged.paginate;

@Entity
@Getter
@FilterDefs(value = {
    @FilterDef(name = "BS.Since",
        defaultCondition = "timestamp >= :since",
        parameters = {@ParamDef(name = "since", type = java.time.ZonedDateTime.class)}),
    @FilterDef(name = "BS.eqBuild",
        defaultCondition = "buildID = :buildId",
        parameters = {@ParamDef(name = "buildId", type = String.class)})
    })
@Filter(name = "BS.Since")
@Filter(name = "BS.eqBuild", condition = "buildID = :buildId")
public class BuildStat extends PanacheEntity {

    public String buildID;

    @Setter
    public ZonedDateTime timestamp;

    @JsonIgnoreProperties("builds")
    @ManyToOne(fetch = FetchType.EAGER)
    public Job job;

    public long total = 0;
    public long filtered = 0;
    public long cached = 0;
    public long errors = 0;
    public long successes = 0;

    public BuildStat(String buildID) {
        this.buildID = buildID;
    }

    public BuildStat(String buildID, ZonedDateTime timestamp) {
        this.buildID = buildID;
        this.timestamp = timestamp;
    }

    //region PanacheQueries
    private static final Sort DEFAULT_SORTING = Sort.by("id");
    public static Paged<BuildStat> getAll(Page page, ZonedDateTime since) {
        PanacheQuery<BuildStat> query = find("from BuildStat", DEFAULT_SORTING);
        if (since != null) {
            query.filter("BS.Since", Parameters.with("since", since));
        }

        return paginate(page, query);
    }

    public static Paged<BuildStat> getByJobID(Page page, long jobID) {
        PanacheQuery<BuildStat> query = find("job.id = :jobId",
            DEFAULT_SORTING,
            Parameters.with("jobId",jobID));
        return Paged.paginate(page, query);
    }

    public static Paged<BuildStat> getByBuildID(Page page, String buildID) {
        PanacheQuery<BuildStat> query = find("buildID", DEFAULT_SORTING, buildID);

        return Paged.paginate(page, query);
    }

    public static BuildStat getLatest() {
        return find("from BuildStat", DEFAULT_SORTING.descending()).firstResult();
    }
    //endregion

    //region SETTERS/GETTERS
    public void setTotal(long total) {
        if (job != null) {
            job.total = job.total - this.total + total;
        }
        this.total = total;
    }

    public void setFiltered(long filtered) {
        if (job != null) {
            job.filtered = job.filtered - this.filtered + filtered;
        }
        this.filtered = filtered;
    }

    public BuildStat() {}

    public void incCached() {
        cached++;
        if (job != null) job.cached++;
    }

    public void incFilter() {
        filtered++;
        if (job != null) job.filtered++;
    }

    public void incError(int amount) {
        errors+=amount;
        if (job != null) job.errors++;
    }

    public void incSuccess(int amount) {
        successes+=amount;
        if (job != null) job.successes++;
    }

    public void setJob(Job job) {
        if (this.job == job) {
            return;
        }
        if (this.job != null) {
            this.job.total -= this.total;
            this.job.filtered -= this.filtered;
            this.job.cached -= this.cached;
            this.job.errors -= this.errors;
            this.job.successes -= this.successes;
            this.job.builds.remove(this);
        }
        this.job = job;
        if (job != null) {
            this.job.total += this.total;
            this.job.filtered += this.filtered;
            this.job.cached += this.cached;
            this.job.errors += this.errors;
            this.job.successes += this.successes;
            this.job.builds.add(this);
        }
    }
    //endregion
}
