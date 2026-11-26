package jp.co.bytex.keycloak.imapauth;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

/**
 * Fail-closed checks for the roles and groups a user inherits when it is created automatically. Only the realm default
 * role root, a fixed set of ordinary built-in roles and explicitly allowlisted identities are accepted.
 */
final class ImapDefaultPermissions {
    private static final int MAX_GRAPH_DEPTH = 100;

    private static final int MAX_GRAPH_SIZE = 10000;

    private static final Set<String> DENIED_REALM_ROLES = Set.of("admin", "create-realm");

    private static final Set<String> MANAGEMENT_CLIENTS = Set.of("realm-management", "security-admin-console",
            "admin-cli");

    private static final Set<String> ORDINARY_REALM_ROLES = Set.of("offline_access", "uma_authorization");

    private static final Set<String> ORDINARY_ACCOUNT_ROLES = Set.of("view-profile", "manage-account",
            "manage-account-links");

    private static final String GROUP_PREFIX = "group:";

    private ImapDefaultPermissions() {
    }

    static void checkDefaults(RealmModel realm) {
        checkDefaults(realm, Allowed.empty());
    }

    static void checkCreatedUser(RealmModel realm, UserModel user) {
        checkCreatedUser(realm, user, Allowed.empty());
    }

    static void checkDefaults(RealmModel realm, Allowed allowed) {
        validateAllowed(realm, allowed);
        RoleModel defaultRole = realm.getDefaultRole();
        if (defaultRole != null) {
            checkRole(defaultRole, defaultRole, realm, allowed, new HashSet<>(), new HashSet<>());
        }

        try (Stream<GroupModel> groups = realm.getDefaultGroupsStream()) {
            groups.forEach(group -> checkGroup(group, realm, allowed, new HashSet<>(), new HashSet<>()));
        }
    }

    static void checkCreatedUser(RealmModel realm, UserModel user, Allowed allowed) {
        checkDefaults(realm, allowed);

        try (Stream<GroupModel> groups = user.getGroupsStream()) {
            groups.forEach(group -> checkGroup(group, realm, allowed, new HashSet<>(), new HashSet<>()));
        }

        try (Stream<RoleModel> roles = user.getRoleMappingsStream()) {
            roles.forEach(
                    role -> checkRole(role, realm.getDefaultRole(), realm, allowed, new HashSet<>(), new HashSet<>()));
        }
    }

    private static void validateAllowed(RealmModel realm, Allowed allowed) {
        for (String identity : allowed.roles()) {
            String[] parts = identity.split(":");
            RoleModel role = realm.getRoleById(parts[parts.length - 1]);
            if (role == null || !identity.equals(roleIdentity(role, realm))) {
                throw new IllegalStateException("unknown role identity");
            }

            checkRole(role, realm.getDefaultRole(), realm, allowed, new HashSet<>(), new HashSet<>());
        }

        for (String identity : allowed.groups()) {
            GroupModel group = realm.getGroupById(identity.substring(GROUP_PREFIX.length()));
            if (group == null) {
                throw new IllegalStateException("unknown group identity");
            }
        }
    }

    /**
     * Returns the stable identity of a role ({@code realm:<role-id>} or {@code client:<client-uuid>:<role-id>}), or
     * throws when the role is unresolved, belongs to another realm or grants management permissions.
     */
    private static String roleIdentity(RoleModel role, RealmModel realm) {
        if (role == null || role.getId() == null) {
            throw new IllegalStateException("unresolved role");
        }

        if (!role.isClientRole()) {
            if (!(role.getContainer() instanceof RealmModel owner && Objects.equals(owner.getId(), realm.getId()))) {
                throw new IllegalStateException("foreign realm role");
            }
            if (DENIED_REALM_ROLES.contains(role.getName())) {
                throw new IllegalStateException("built-in management role");
            }
            return "realm:" + role.getId();
        }

        if (!(role.getContainer() instanceof ClientModel client
                && client.getRealm() != null
                && Objects.equals(client.getRealm().getId(), realm.getId())
                && client.getId() != null)) {
            throw new IllegalStateException("foreign client role");
        }

        ClientModel masterAdminClient = realm.getMasterAdminClient();
        boolean managementClient = MANAGEMENT_CLIENTS.contains(client.getClientId())
                || (masterAdminClient != null && Objects.equals(masterAdminClient.getId(), client.getId()))
                || ("master".equals(realm.getName()) && client.getClientId() != null
                        && client.getClientId().endsWith("-realm"));
        if (managementClient) {
            throw new IllegalStateException("management client role");
        }
        return "client:" + client.getId() + ":" + role.getId();
    }

    private static void checkRole(RoleModel role, RoleModel defaultRole, RealmModel realm, Allowed allowed,
            Set<String> path, Set<String> checked) {
        String identity = roleIdentity(role, realm);
        if (checked.contains(identity)) {
            return;
        }
        if (!path.add(identity) || path.size() > MAX_GRAPH_DEPTH || checked.size() > MAX_GRAPH_SIZE) {
            throw new IllegalStateException("cyclic or excessive role graph");
        }

        boolean defaultRoleRoot = defaultRole != null && Objects.equals(defaultRole.getId(), role.getId())
                && !role.isClientRole();
        boolean ordinaryRole = isOrdinaryRole(role);
        if (!defaultRoleRoot && !ordinaryRole && !allowed.roles().contains(identity)) {
            throw new IllegalStateException("unclassified role");
        }

        if (role.isComposite()) {
            try (Stream<RoleModel> children = role.getCompositesStream()) {
                children.forEach(child -> checkRole(child, defaultRole, realm, allowed, path, checked));
            }
        }

        path.remove(identity);
        checked.add(identity);
    }

    private static boolean isOrdinaryRole(RoleModel role) {
        if (role.isClientRole()) {
            ClientModel client = (ClientModel) role.getContainer();
            return "account".equals(client.getClientId()) && ORDINARY_ACCOUNT_ROLES.contains(role.getName());
        }
        return ORDINARY_REALM_ROLES.contains(role.getName());
    }

    /**
     * Requires the group, every ancestor and every descendant to be allowlisted, and checks the roles mapped to the
     * group and its ancestors.
     */
    private static void checkGroup(GroupModel group, RealmModel realm, Allowed allowed, Set<String> path,
            Set<String> checked) {
        if (group == null || group.getId() == null || !allowed.groups().contains(GROUP_PREFIX + group.getId())
                || realm.getGroupById(group.getId()) == null) {
            throw new IllegalStateException("unclassified group");
        }
        if (checked.contains(group.getId())) {
            return;
        }
        if (!path.add(group.getId()) || path.size() > MAX_GRAPH_DEPTH || checked.size() > MAX_GRAPH_SIZE) {
            throw new IllegalStateException("cyclic or excessive group graph");
        }

        Set<String> ancestors = new HashSet<>();
        for (GroupModel current = group; current != null; current = current.getParent()) {
            if (current.getId() == null
                    || !ancestors.add(current.getId())
                    || ancestors.size() > MAX_GRAPH_DEPTH
                    || !allowed.groups().contains(GROUP_PREFIX + current.getId())
                    || realm.getGroupById(current.getId()) == null) {
                throw new IllegalStateException("unclassified or cyclic parent group");
            }

            try (Stream<RoleModel> roles = current.getRoleMappingsStream()) {
                roles.forEach(role -> checkRole(role, realm.getDefaultRole(), realm, allowed, new HashSet<>(),
                        new HashSet<>()));
            }
        }

        try (Stream<GroupModel> children = group.getSubGroupsStream()) {
            children.forEach(child -> checkGroup(child, realm, allowed, path, checked));
        }

        path.remove(group.getId());
        checked.add(group.getId());
    }

    record Allowed(Set<String> roles, Set<String> groups) {
        private static final int MAX_ALLOWLIST_LENGTH = 16000;

        private static final String GROUP_TOKEN_PATTERN = "group:[A-Za-z0-9_-]+";

        private static final String ROLE_TOKEN_PATTERN = "(?:realm:[A-Za-z0-9_-]+|client:[A-Za-z0-9_-]+:[A-Za-z0-9_-]+)";

        static Allowed empty() {
            return new Allowed(Set.of(), Set.of());
        }

        static Allowed parse(Map<String, String> values) {
            Map<String, String> configurationValues = values == null ? Map.of() : values;
            return new Allowed(
                    tokens(configurationValues.getOrDefault("allowedDefaultRoles", ""), false),
                    tokens(configurationValues.getOrDefault("allowedDefaultGroups", ""), true));
        }

        private static Set<String> tokens(String text, boolean group) {
            if (text == null || text.length() > MAX_ALLOWLIST_LENGTH) {
                throw new IllegalArgumentException("allowlist");
            }
            if (text.isBlank()) {
                return Set.of();
            }

            String pattern = group ? GROUP_TOKEN_PATTERN : ROLE_TOKEN_PATTERN;
            Set<String> result = new HashSet<>();
            for (String rawToken : text.split(",", -1)) {
                String token = rawToken.trim();
                if (!token.matches(pattern) || !result.add(token)) {
                    throw new IllegalArgumentException("allowlist identity");
                }
            }
            return Set.copyOf(result);
        }
    }
}
