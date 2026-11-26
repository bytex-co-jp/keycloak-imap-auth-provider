package jp.co.bytex.keycloak.imapauth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.keycloak.models.ClientModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleContainerModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

/**
 * 許可リスト未指定時の保守的な既定権限検査を検証する。
 *
 * <p>
 * 通常の組み込み Role、管理権限、循環および Realm をまたぐ Role を Mock で確認する。
 */
class ImapDefaultPermissionsTest {

    RealmModel realm = mock(RealmModel.class);

    RoleModel role(String id, String name, RoleContainerModel owner, boolean client) {
        RoleModel role = mock(RoleModel.class);
        when(role.getId()).thenReturn(id);
        when(role.getName()).thenReturn(name);
        when(role.getContainer()).thenReturn(owner);
        when(role.isClientRole()).thenReturn(client);
        return role;
    }

    void defaults(RoleModel defaultRole) {
        when(realm.getId()).thenReturn("realm");
        when(realm.getDefaultRole()).thenReturn(defaultRole);
    }

    @Test
    void ordinaryCompositeDefaultsAllowed() {
        RoleModel defaultRole = role("root", "default-roles", realm, false);
        RoleModel offline = role("offline", "offline_access", realm, false);
        defaults(defaultRole);
        when(defaultRole.isComposite()).thenReturn(true);
        when(defaultRole.getCompositesStream()).thenAnswer(i -> Stream.of(offline));
        assertDoesNotThrow(() -> ImapDefaultPermissions.checkDefaults(realm));
    }

    @Test
    void ordinaryRealmDefaultRoleWithAccountCompositesAllowed() {
        RoleModel root = role("root", "default-roles-test", realm, false);
        RoleModel offline = role("offline", "offline_access", realm, false);
        RoleModel authorization = role("uma", "uma_authorization", realm, false);
        ClientModel account = mock(ClientModel.class);
        when(account.getId()).thenReturn("account-id");
        when(account.getClientId()).thenReturn("account");
        when(account.getRealm()).thenReturn(realm);
        RoleModel manage = role("manage", "manage-account", account, true);
        RoleModel profile = role("profile", "view-profile", account, true);
        RoleModel links = role("links", "manage-account-links", account, true);
        defaults(root);
        when(root.isComposite()).thenReturn(true);
        when(root.getCompositesStream()).thenAnswer(i -> Stream.of(offline, authorization, manage, profile));
        when(manage.isComposite()).thenReturn(true);
        when(manage.getCompositesStream()).thenAnswer(i -> Stream.of(links));
        assertDoesNotThrow(() -> ImapDefaultPermissions.checkDefaults(realm));
    }

    @Test
    void nestedManagementClientRoleRejected() {
        ClientModel client = mock(ClientModel.class);
        when(client.getClientId()).thenReturn("realm-management");
        when(client.getRealm()).thenReturn(realm);
        RoleModel defaultRole = role("root", "default", realm, false);
        RoleModel admin = role("admin", "realm-admin", client, true);
        defaults(defaultRole);
        when(defaultRole.isComposite()).thenReturn(true);
        when(defaultRole.getCompositesStream()).thenAnswer(i -> Stream.of(admin));
        assertThrows(IllegalStateException.class, () -> ImapDefaultPermissions.checkDefaults(realm));
    }

    @Test
    void customRealmRoleCannotBeAssumedSafe() {
        RoleModel defaultRole = role("root", "default", realm, false);
        RoleModel custom = role("custom", "application-operator", realm, false);
        defaults(defaultRole);
        when(defaultRole.isComposite()).thenReturn(true);
        when(defaultRole.getCompositesStream()).thenAnswer(i -> Stream.of(custom));
        assertThrows(IllegalStateException.class, () -> ImapDefaultPermissions.checkDefaults(realm));
    }

    @Test
    void cycleRejected() {
        RoleModel defaultRole = role("root", "default", realm, false);
        defaults(defaultRole);
        when(defaultRole.isComposite()).thenReturn(true);
        when(defaultRole.getCompositesStream()).thenAnswer(i -> Stream.of(defaultRole));
        assertThrows(IllegalStateException.class, () -> ImapDefaultPermissions.checkDefaults(realm));
    }

    @Test
    void accountRoleFromAnotherRealmRejected() {
        when(realm.getId()).thenReturn("realm");
        ClientModel client = mock(ClientModel.class, RETURNS_DEEP_STUBS);
        when(client.getClientId()).thenReturn("account");
        when(client.getRealm().getId()).thenReturn("other");
        RoleModel foreign = role("foreign", "manage-account", client, true);
        UserModel user = mock(UserModel.class);
        when(user.getRoleMappingsStream()).thenAnswer(i -> Stream.of(foreign));
        assertThrows(IllegalStateException.class, () -> ImapDefaultPermissions.checkCreatedUser(realm, user));
    }
}
