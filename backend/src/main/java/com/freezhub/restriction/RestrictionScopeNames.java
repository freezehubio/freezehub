package com.freezhub.restriction;

import com.freezhub.catalog.ApplicationRepository;
import com.freezhub.catalog.EnvironmentRepository;
import com.freezhub.catalog.TeamRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Component;

/**
 * A restriction's scope, as names a person can read (FZ-186).
 *
 * <p>Scope is stored as catalog ids, and every API response returns ids — the frontend
 * resolves them against a catalog it has already fetched. An announcement has no such
 * luxury: it arrives in Slack with no catalog beside it, and "applies to 3, 7" tells the
 * reader nothing.
 *
 * <p>Lives in {@code restriction} rather than {@code notification} because it is scope
 * that is being described, and because {@code CatalogDeletion} sets the direction
 * explicitly: <em>restriction may depend on catalog, not the other way round</em>.
 *
 * <p><b>Every id resolves.</b> The scope tables reference the catalog with non-cascading
 * foreign keys, so a catalog entry a restriction still names cannot be deleted — the
 * database refuses and the API returns {@code 409}. There is therefore no partially
 * resolvable scope to render, which is what makes it safe for an announcement to state a
 * scope as fact.
 *
 * <p>Sorted, so the same freeze reads the same way every time it is announced. A set
 * iterating in a different order between the SCHEDULED and ACTIVATED messages would look
 * like the scope had changed.
 */
@Component
public class RestrictionScopeNames {

    /**
     * An empty list means <em>every</em> one of that kind, not none (`01-domain.md`).
     * Callers must word it that way; rendering an empty dimension as blank inverts the
     * rule and turns "all production deployments" into "nothing".
     */
    public record ScopeNames(List<String> teams, List<String> applications, List<String> environments) {
    }

    private final TeamRepository teams;
    private final ApplicationRepository applications;
    private final EnvironmentRepository environments;

    public RestrictionScopeNames(TeamRepository teams, ApplicationRepository applications,
                                 EnvironmentRepository environments) {
        this.teams = teams;
        this.applications = applications;
        this.environments = environments;
    }

    public ScopeNames of(ChangeRestriction restriction) {
        return new ScopeNames(
                namesOf(restriction.getTeamIds(), teams, com.freezhub.catalog.Team::getName),
                namesOf(restriction.getApplicationIds(), applications,
                        com.freezhub.catalog.Application::getName),
                namesOf(restriction.getEnvironmentIds(), environments,
                        com.freezhub.catalog.Environment::getName));
    }

    private <T> List<String> namesOf(Set<Long> ids, CrudRepository<T, Long> repository,
                                     Function<T, String> name) {
        if (ids.isEmpty()) {
            // No query for a wildcard. Also the common case: most freezes name an
            // environment and leave the other two dimensions open.
            return List.of();
        }
        List<String> resolved = new java.util.ArrayList<>();
        repository.findAllById(ids).forEach(entity -> resolved.add(name.apply(entity)));
        resolved.sort(Comparator.naturalOrder());
        return List.copyOf(resolved);
    }

}
