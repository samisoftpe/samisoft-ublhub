package io.github.project.openubl.ublhub.security;

import io.github.project.openubl.ublhub.models.jpa.entities.ProjectEntity;
import io.quarkus.security.identity.SecurityIdentity;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * UBLHub is deployed as a private Samisoft service. A stable internal owner
 * prevents projects from becoming inaccessible when the runtime identity
 * changes between deployments.
 */
@ApplicationScoped
public class CurrentUserProvider {

    public static final String SAMISOFT_SERVICE_USER = "samisoft-service";

    @Inject
    SecurityIdentity securityIdentity;

    public String getUsername() {
        return SAMISOFT_SERVICE_USER;
    }

    public Set<String> getAccessibleUsernames() {
        Set<String> usernames = new LinkedHashSet<>();
        usernames.add(SAMISOFT_SERVICE_USER);
        if (securityIdentity != null && securityIdentity.getPrincipal() != null) {
            String legacyUsername = securityIdentity.getPrincipal().getName();
            if (legacyUsername != null && !legacyUsername.isBlank()) {
                usernames.add(legacyUsername);
            }
        }
        return usernames;
    }

    public boolean hasAnyRole(ProjectEntity project, String... roles) {
        if (project == null) {
            return false;
        }
        return getAccessibleUsernames().stream().anyMatch(username ->
                roles.length == 0
                        ? project.hasAnyRole(username)
                        : project.hasAnyRole(username, roles)
        );
    }
}
