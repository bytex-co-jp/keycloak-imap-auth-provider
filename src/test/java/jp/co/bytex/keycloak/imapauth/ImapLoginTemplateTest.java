package jp.co.bytex.keycloak.imapauth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.forms.login.freemarker.model.AuthenticationContextBean;
import org.keycloak.models.UserModel;

import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.MultiTemplateLoader;
import freemarker.cache.TemplateLoader;
import freemarker.core.HTMLOutputFormat;
import freemarker.template.Configuration;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateScalarModel;

/**
 * 同じ Keycloak バージョンの実際のレイアウトを使用し、フォームの描画と安全な再表示を検証する。
 */
class ImapLoginTemplateTest {
    String render(String theme, String email, boolean failed) throws Exception {
        Configuration configuration = new Configuration(Configuration.VERSION_2_3_32);
        configuration.setDefaultEncoding(StandardCharsets.UTF_8.name());
        configuration.setOutputFormat(HTMLOutputFormat.INSTANCE);
        configuration.setTemplateLoader(new MultiTemplateLoader(new TemplateLoader[]{
                new ClassTemplateLoader(getClass(), "/theme-resources/templates"),
                new ClassTemplateLoader(getClass(), "/theme/" + theme + "/login"),
                new ClassTemplateLoader(getClass(), "/theme/base/login")}));
        Properties properties = new Properties();
        try (var source = getClass().getResourceAsStream("/theme/" + theme + "/login/theme.properties")) {
            assertNotNull(source);
            properties.load(source);
        }
        Map<String, Object> model = new HashMap<>();
        model.put("properties", properties);
        model.put("realm", Map.of("name", "TEST", "displayName", "TEST", "displayNameHtml", "TEST",
                "internationalizationEnabled", false, "loginWithEmailAllowed", true,
                "registrationEmailAsUsername", false));
        model.put("url", Map.of("loginAction", "/login-actions/authenticate?session_code=test",
                "resourcesPath", "/resources/test", "resourcesCommonPath", "/resources/common",
                "ssoLoginInOtherTabsUrl", "/sso-check", "loginRestartFlowUrl", "/restart"));
        var context = mock(AuthenticationFlowContext.class, RETURNS_DEEP_STUBS);
        when(context.getUser()).thenReturn(failed ? mock(UserModel.class) : null);
        when(context.getAuthenticationSession().getAuthNote(anyString()))
                .thenAnswer(invocation -> "ATTEMPTED_USERNAME".equals(invocation.getArgument(0)) ? email : null);
        when(context.getAuthenticationSelections()).thenReturn(List.of());
        model.put("auth", new AuthenticationContextBean(context, null));
        model.put("lang", "en");
        model.put("title", "TEST login");
        model.put("pageId", "imap-login");
        model.put("darkMode", false);
        model.put("imapEmail", email);
        model.put("password", "synthetic-secret-never-render");
        model.put("msg", (TemplateMethodModelEx) arguments -> {
            String key = ((TemplateScalarModel) arguments.get(0)).getAsString();
            return switch (key) {
                case "email" -> "Email";
                case "password" -> "Password";
                case "doLogIn" -> "Sign In";
                case "loginTitle" -> "Sign in to TEST";
                case "loginTitleHtml" -> "TEST";
                case "imapLoginFailed" -> "Login failed.";
                default -> key;
            };
        });
        model.put("kcSanitize",
                (TemplateMethodModelEx) arguments -> ((TemplateScalarModel) arguments.get(0)).getAsString());
        if (failed) {
            model.put("message", Map.of("type", "error", "summary", "Login failed."));
        }
        var rawAuth = (AuthenticationContextBean) model.get("auth");
        assertEquals(failed, rawAuth.showUsername());
        java.util.concurrent.atomic.AtomicReference<java.util.function.Function<Map<String, Object>, Map<String, Object>>> mapper = new java.util.concurrent.atomic.AtomicReference<>();
        var forms = context.form();
        doAnswer(invocation -> {
            mapper.set(invocation.getArgument(0));
            return forms;
        }).when(forms).setAttributeMapper(any());
        new ImapAuthenticator().authenticate(context);
        assertNotNull(mapper.get());
        model = mapper.get().apply(model);
        StringWriter output = new StringWriter();
        configuration.getTemplate("imap-login.ftl").process(model, output);
        return output.toString();
    }

    @Test
    void nativeThemesRenderFieldsStylesAndRealmTitle() throws Exception {
        for (String theme : List.of("keycloak", "keycloak.v2")) {
            String html = render(theme, "user@example.invalid", false);
            assertTrue(html.contains("Sign in to TEST"));
            assertTrue(html.contains("name=\"username\" type=\"email\""));
            assertTrue(html.contains("name=\"password\" type=\"password\""));
            assertTrue(html.contains("autocomplete=\"username\""));
            assertTrue(html.contains("autocomplete=\"current-password\""));
            assertTrue(html.contains("action=\"/login-actions/authenticate?session_code=test\""));
            assertTrue(html.contains("method=\"post\""));
            assertTrue(html.contains("rel=\"stylesheet\""));
            assertTrue(html.contains("/resources/"));
            assertTrue(html.contains("dataset.submitted"));
            assertTrue(html.contains("kc-login"));
            assertFalse(html.contains("synthetic-secret-never-render"));
            assertEquals(2, html.split("<input\\b", -1).length - 1);
            assertFalse(html.contains("id=\"kc-attempted-username\""));
        }
    }

    @Test
    void failedFormEscapesEmailAndDoesNotEchoPassword() throws Exception {
        for (String theme : List.of("keycloak", "keycloak.v2")) {
            String html = render(theme, "\"><script>alert(1)</script>", true);
            assertTrue(html.contains("Login failed."));
            assertFalse(html.contains("<script>alert(1)</script>"));
            assertFalse(html.contains("synthetic-secret-never-render"));
            assertTrue(html.contains("&lt;script&gt;"));
        }
    }

    @Test
    void failedLoginContainsOnlyOneInputArea() throws Exception {
        for (String theme : List.of("keycloak", "keycloak.v2")) {
            String html = render(theme, "user@example.invalid", true);
            assertFalse(html.contains("id=\"kc-attempted-username\""), theme);
            assertEquals(2, html.split("<input\\b", -1).length - 1, theme);
            assertEquals(1, html.split("id=\"kc-form-login\"", -1).length - 1, theme);
            assertEquals(1, html.split("id=\"kc-login\"", -1).length - 1, theme);
            assertTrue(html.contains("value=\"user@example.invalid\""));
            assertFalse(html.contains("synthetic-secret-never-render"));
            int formStart = html.indexOf("<form id=\"kc-form-login\"");
            int formEnd = html.indexOf("</form>", formStart);
            int error = html.indexOf("Login failed.");
            assertTrue(error > formStart && error < formEnd, theme);
        }
    }
}
