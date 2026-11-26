package jp.co.bytex.keycloak.imapauth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleContainerModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

/**
 * 既定権限の許可リスト、主体の区別と再帰的な拒否規則を検証する。
 *
 * <p>
 * Role や Group の設定変更後も、管理権限や未承認の子要素が拒否されることを確認する。
 */
class ImapAllowlistTest {

    RealmModel realm;

    RoleModel defaultRole;

    RoleModel employee;

    GroupModel group;

    @BeforeEach
    void setup() {
        realm = mock(RealmModel.class);
        when(realm.getId()).thenReturn("realm");
        when(realm.getName()).thenReturn("test");
        defaultRole = role("root", "default", realm, false);
        employee = role("employee-id", "employee", realm, false);
        when(realm.getDefaultRole()).thenReturn(defaultRole);
        when(defaultRole.isComposite()).thenReturn(true);
        when(defaultRole.getCompositesStream()).thenAnswer(i -> Stream.of(employee));
        group = mock(GroupModel.class);
        when(group.getId()).thenReturn("employees-id");
        when(realm.getGroupById("employees-id")).thenReturn(group);
    }

    RoleModel role(String id, String name, RoleContainerModel owner, boolean client) {
        RoleModel role = mock(RoleModel.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(role.getContainer()).thenReturn(owner);
        when(role.isClientRole()).thenReturn(client);
        when(realm.getRoleById(id)).thenReturn(role);
        return role;
    }

    ImapDefaultPermissions.Allowed allow(String roles, String groups) {
        return ImapDefaultPermissions.Allowed
                .parse(Map.of("allowedDefaultRoles", roles, "allowedDefaultGroups", groups));
    }

    void check(String roles, String groups) {
        ImapDefaultPermissions.checkDefaults(realm, allow(roles, groups));
    }

    @Test
    void emptyKeepsConservativePolicy() {
        assertThrows(IllegalStateException.class, () -> check("", ""));
    }

    @Test
    void customOrdinaryRoleAllowed() {
        assertDoesNotThrow(() -> check("realm:employee-id", ""));
    }

    @Test
    void missingRoleRejected() {
        assertThrows(IllegalStateException.class, () -> check("realm:missing", ""));
    }

    @Test
    void ordinaryGroupAllowed() {
        when(realm.getDefaultGroupsStream()).thenAnswer(i -> Stream.of(group));
        when(group.getRoleMappingsStream()).thenAnswer(i -> Stream.of(employee));
        assertDoesNotThrow(() -> check("realm:employee-id", "group:employees-id"));
    }

    @Test
    void unlistedGroupRejected() {
        when(realm.getDefaultGroupsStream()).thenAnswer(i -> Stream.of(group));
        assertThrows(IllegalStateException.class, () -> check("realm:employee-id", ""));
    }

    RoleModel management() {
        ClientModel client = mock(ClientModel.class);
        when(client.getId()).thenReturn("management-id");
        when(client.getClientId()).thenReturn("realm-management");
        when(client.getRealm()).thenReturn(realm);
        return role("admin-id", "view-users", client, true);
    }

    @Test
    void managementRoleDeniedEvenWhenListed() {
        RoleModel admin = management();
        when(defaultRole.getCompositesStream()).thenAnswer(i -> Stream.of(admin));
        assertThrows(IllegalStateException.class, () -> check("client:management-id:admin-id", ""));
    }

    @Test
    void compositeMemberRecheckedAfterChange() {
        check("realm:employee-id", "");
        RoleModel admin = management();
        when(employee.isComposite()).thenReturn(true);
        when(employee.getCompositesStream()).thenAnswer(i -> Stream.of(admin));
        assertThrows(IllegalStateException.class, () -> check("realm:employee-id,client:management-id:admin-id", ""));
    }

    @Test
    void unlistedCompositeMemberRejected() {
        RoleModel child = role("child-id", "ordinary", realm, false);
        when(employee.isComposite()).thenReturn(true);
        when(employee.getCompositesStream()).thenAnswer(i -> Stream.of(child));
        assertThrows(IllegalStateException.class, () -> check("realm:employee-id", ""));
    }

    @Test
    void parentManagementDenied() {
        GroupModel parent = mock(GroupModel.class);
        when(parent.getId()).thenReturn("parent-id");
        when(realm.getGroupById("parent-id")).thenReturn(parent);
        when(group.getParent()).thenReturn(parent);
        RoleModel admin = management();
        when(parent.getRoleMappingsStream()).thenAnswer(i -> Stream.of(admin));
        when(realm.getDefaultGroupsStream()).thenAnswer(i -> Stream.of(group));
        assertThrows(IllegalStateException.class,
                () -> check("realm:employee-id", "group:employees-id,group:parent-id"));
    }

    @Test
    void nestedManagementDenied() {
        GroupModel child = mock(GroupModel.class);
        when(child.getId()).thenReturn("child-id");
        when(realm.getGroupById("child-id")).thenReturn(child);
        when(group.getSubGroupsStream()).thenAnswer(i -> Stream.of(child));
        RoleModel admin = management();
        when(child.getRoleMappingsStream()).thenAnswer(i -> Stream.of(admin));
        when(realm.getDefaultGroupsStream()).thenAnswer(i -> Stream.of(group));
        assertThrows(IllegalStateException.class,
                () -> check("realm:employee-id", "group:employees-id,group:child-id"));
    }

    @Test
    void sameNameDifferentIdRejected() {
        RoleModel other = role("other-id", "employee", realm, false);
        when(defaultRole.getCompositesStream()).thenAnswer(i -> Stream.of(other));
        assertThrows(IllegalStateException.class, () -> check("realm:employee-id", ""));
    }

    @Test
    void wrongClientIdentityRejected() {
        ClientModel client = mock(ClientModel.class);
        when(client.getId()).thenReturn("actual-client");
        when(client.getClientId()).thenReturn("application");
        when(client.getRealm()).thenReturn(realm);
        role("client-role", "employee", client, true);
        assertThrows(IllegalStateException.class, () -> check("realm:employee-id,client:wrong-client:client-role", ""));
    }

    @Test
    void invalidSyntaxRejected() {
        for (String value : List.of("*", "employee", "realm:*", "realm:employee-id,",
                "realm:employee-id,realm:employee-id", "client:role")) {
            assertThrows(IllegalArgumentException.class, () -> allow(value, ""));
        }
        assertThrows(IllegalArgumentException.class, () -> allow("", "/employees"));
    }

    @Test
    void postCreationUnexpectedRoleRejected() {
        UserModel user = mock(UserModel.class);
        RoleModel extra = role("extra", "unknown", realm, false);
        when(user.getRoleMappingsStream()).thenAnswer(i -> Stream.of(extra));
        assertThrows(IllegalStateException.class,
                () -> ImapDefaultPermissions.checkCreatedUser(realm, user, allow("realm:employee-id", "")));
    }

    @Test
    void parentCycleRejected() {
        when(group.getParent()).thenReturn(group);
        when(realm.getDefaultGroupsStream()).thenAnswer(i -> Stream.of(group));
        assertThrows(IllegalStateException.class, () -> check("realm:employee-id", "group:employees-id"));
    }
}
