<#import "template.ftl" as layout>
<#assign modernInput = properties.kcFormLabelTextClass??>
<@layout.registrationLayout displayMessage=false; section>
    <#if section == "header">
        ${msg("loginTitle", realm.displayName!realm.name)}
    <#elseif section == "form">
        <div id="kc-form">
            <div id="kc-form-wrapper">
                <form id="kc-form-login" class="${properties.kcFormClass!}" action="${url.loginAction}" method="post"
                      onsubmit="if (this.dataset.submitted === 'true') return false; this.dataset.submitted = 'true'; this.querySelector('button[type=submit]').disabled = true; return true;">
                    <#if message?has_content && message.type == "error">
                        <div id="imap-login-error" class="alert-error ${properties.kcAlertClass!} pf-m-danger" role="alert">
                            <span class="${properties.kcAlertTitleClass!}">${msg("imapLoginFailed")}</span>
                        </div>
                    </#if>
                    <div class="${properties.kcFormGroupClass!}">
                        <div class="${properties.kcFormGroupLabelClass!}">
                            <label for="username" class="${properties.kcLabelClass!}">${msg("email")}</label>
                        </div>
                        <#if modernInput><span class="${properties.kcInputClass!}"></#if>
                        <input id="username" name="username" type="email" value="${imapEmail!''}"
                               <#if !modernInput>class="${properties.kcInputClass!}"</#if>
                               autocomplete="username" autocapitalize="none" spellcheck="false" dir="ltr" required autofocus />
                        <#if modernInput></span></#if>
                    </div>
                    <div class="${properties.kcFormGroupClass!}">
                        <div class="${properties.kcFormGroupLabelClass!}">
                            <label for="password" class="${properties.kcLabelClass!}">${msg("password")}</label>
                        </div>
                        <#if modernInput><span class="${properties.kcInputClass!}"></#if>
                        <input id="password" name="password" type="password"
                               <#if !modernInput>class="${properties.kcInputClass!}"</#if>
                               autocomplete="current-password" required />
                        <#if modernInput></span></#if>
                    </div>
                    <div id="kc-form-buttons" class="${properties.kcFormGroupClass!} ${properties.kcFormActionGroupClass!}">
                        <button id="kc-login" name="login" type="submit"
                                class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}">${msg("doLogIn")}</button>
                    </div>
                </form>
            </div>
        </div>
    </#if>
</@layout.registrationLayout>
