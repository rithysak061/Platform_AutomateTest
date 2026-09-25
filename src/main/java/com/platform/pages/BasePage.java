package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.Map;

/**
 * Shared alert/popup handling for page objects that hit the site's generic "AlertBoard"
 * popup/modal system (promo popups, result alerts, confirm/cancel prompts), plus the shared
 * amount-entry/submit/bind-account form components that Deposit and Withdraw both build on (same
 * CSS classes and modal structure on both pages). Ported from the Python Platform_automation
 * project's pages/basePage.py.
 */
public class BasePage {

    // Scoped to the currently-shown overlay ("...show__5duyM"), not just any
    // ".AlertBoard_content__A6ycP" in the DOM: a dismissed alert/prompt can be left behind
    // hidden rather than removed, so an unscoped selector can pick up that stale, invisible
    // node (still with its old text) instead of the new alert that replaced it.
    private static final String ALERT_SELECTOR = ".AlertBoard_bg__mCPnq.AlertBoard_show__5duyM .AlertBoard_content__A6ycP";
    private static final String ALERT_OVERLAY = ".AlertBoard_bg__mCPnq.AlertBoard_show__5duyM";

    private static final String AMOUNT_INPUT = "input[placeholder*='Min']";
    private static final String SUBMIT_BUTTON = "button.SubmitButton_submit__luKqw";
    private static final String BIND_MODAL = "#main-modal";
    private static final String BIND_FIELD_ITEM = ".ContentTemplate_bank-detail-item__nyYsv";
    private static final String BIND_FIELD_LABEL = ".ContentTemplate_bank-detail-label__LaWPg";
    private static final String FIELD_ERROR_SELECTOR = ".ContentTemplate_error-message__bMRT3";

    protected final Page page;

    protected BasePage(Page page) {
        this.page = page;
    }

    /**
     * Opens the given country's dial-code dropdown, used by any phone-number field. The trigger
     * is found by its own id ("rfs-btn"), not by matching the currently-displayed dial code's
     * text: a caller that switches country twice (e.g. Hong Kong, then China) would have the dial
     * code change out from under it after the first switch, so matching on a fixed/default code
     * text would stop finding the trigger after that first switch and silently fail to open the
     * dropdown for the second one.
     */
    public void selectCountry(String countryName) {
        page.locator("#rfs-btn").click();
        page.waitForTimeout(300);
        page.getByText(countryName, new Page.GetByTextOptions().setExact(true)).first().click();
        page.waitForTimeout(300);
    }

    /** The first non-blank inline field-validation message currently shown on the page. */
    public String getFieldError() {
        return getFieldError(5000);
    }

    public String getFieldError(int timeoutMs) {
        page.waitForFunction(
                "(sel) => Array.from(document.querySelectorAll(sel)).some((el) => el.innerText.trim().length > 0)",
                FIELD_ERROR_SELECTOR,
                new Page.WaitForFunctionOptions().setTimeout(timeoutMs));
        for (String text : page.locator(FIELD_ERROR_SELECTOR).allInnerTexts()) {
            if (!text.isBlank()) {
                return text;
            }
        }
        return "";
    }

    public boolean isLoggedIn() {
        return page.getByText("Log out", new Page.GetByTextOptions().setExact(true)).isVisible();
    }

    /**
     * True once the "Phone verification code" one-digit-per-box entry modal is showing - used by
     * the register, forgot-password and account-activation flows alike, since they all share the
     * same underlying phone-OTP component.
     */
    public boolean phoneOtpModalVisible() {
        return phoneOtpModalVisible(8000);
    }

    public boolean phoneOtpModalVisible(int timeoutMs) {
        try {
            page.getByText("Phone verification code").first()
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Fills the one-digit-per-box phone verification code (e.g. "1111"). */
    public void enterOtp(String otp) {
        Locator boxes = page.locator("input[maxlength='1']");
        for (int i = 0; i < otp.length(); i++) {
            boxes.nth(i).fill(String.valueOf(otp.charAt(i)));
        }
        page.waitForTimeout(300);
    }

    /**
     * The "...use your phone scan to send" line is split across multiple DOM nodes, so a
     * getByText match (which only matches text within a single element) never finds it even when
     * the modal is genuinely showing. Checking the page's full rendered text instead sidesteps
     * that. A short timeout on innerText keeps this from hanging its full default timeout if the
     * page happens to be mid-navigation right when it's called - treated as "not there yet"
     * rather than eating the whole polling budget in one go.
     */
    private boolean serverVerificationTextPresent() {
        try {
            String bodyText = page.locator("body").innerText(new Locator.InnerTextOptions().setTimeout(2000));
            return bodyText.contains("use your phone scan to send");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * True once the QR-code / server-verification activation modal is showing (used for China
     * numbers, and for any number once an account's anti-abuse throttling switches it to this
     * flow).
     */
    public boolean qrActivationVisible(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (serverVerificationTextPresent()) {
                return true;
            }
            page.waitForTimeout(200);
        }
        return false;
    }

    public void submitActivation() {
        page.locator("button", new Page.LocatorOptions().setHasText("ACTIVATE ACCOUNT")).first()
                .click(new Locator.ClickOptions().setTimeout(8000));
        page.waitForTimeout(1500);
    }

    public void enterAmount(int amount) {
        dismissPromoPopup();
        page.locator(AMOUNT_INPUT).fill(String.valueOf(amount));
    }

    public void submit() {
        dismissPromoPopup();
        try {
            page.locator(SUBMIT_BUTTON).click(new Locator.ClickOptions().setTimeout(8000));
            return;
        } catch (Exception ignored) {
        }
        dismissPromoPopup();
        page.locator(SUBMIT_BUTTON).click(new Locator.ClickOptions().setForce(true));
    }

    public boolean isBound() {
        return page.getByText("Click [Add] to bind Banking details").count() == 0;
    }

    /**
     * Binds a single-field account (e.g. a wallet address or phone number). Targets the first
     * *fillable* input rather than simply the first one in DOM order - some channels (e.g.
     * Withdraw's ATM Machine) put a disabled, pre-filled "Full name" field ahead of the actual
     * account field in the modal.
     */
    public void bindAccount(String value) {
        if (isBound()) {
            return;
        }
        openBindModal();
        Locator modal = page.locator(BIND_MODAL).last();

        Locator inputs = modal.locator("input");
        Locator targetInput = null;
        for (int i = 0; i < inputs.count(); i++) {
            if (!inputs.nth(i).isDisabled()) {
                targetInput = inputs.nth(i);
                break;
            }
        }
        if (targetInput == null) {
            throw new IllegalStateException("No fillable (non-disabled) input found in the bind modal.");
        }
        targetInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3000));
        targetInput.fill(value);

        submitBindModal(modal);
    }

    /**
     * Binds a multi-field account (e.g. bank transfer/FPS), matched to each field by its visible
     * label. Label-based matching is used instead of field order because some of these forms
     * auto-fill and lock a field (e.g. "Full name") once another field is filled in, and the
     * visible field order differs between option types.
     */
    public void bindAccount(Map<String, String> fieldsByLabel) {
        if (isBound()) {
            return;
        }
        openBindModal();
        Locator modal = page.locator(BIND_MODAL).last();

        Locator fields = modal.locator(BIND_FIELD_ITEM);
        for (int i = 0; i < fields.count(); i++) {
            Locator field = fields.nth(i);
            Locator targetInput = field.locator("input");
            if (targetInput.count() == 0) {
                continue;
            }
            String labelText = field.locator(BIND_FIELD_LABEL).first().innerText();
            for (Map.Entry<String, String> entry : fieldsByLabel.entrySet()) {
                if (labelText.contains(entry.getKey())) {
                    if (!targetInput.first().isDisabled()) {
                        targetInput.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3000));
                        targetInput.first().fill(entry.getValue());
                        if (!entry.getValue().equals(targetInput.first().inputValue())) {
                            targetInput.first().fill(entry.getValue());
                        }
                    }
                    break;
                }
            }
        }

        submitBindModal(modal);
    }

    private void openBindModal() {
        dismissPromoPopup();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add").setExact(true)).click();
        page.waitForTimeout(1200);

        Locator modal = page.locator(BIND_MODAL).last();
        modal.locator(BIND_FIELD_ITEM).first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(5000));
        selectDefaultDropdownChoices(modal);
    }

    /** Any dropdown left on its placeholder option (e.g. Bank Name) is defaulted to its first real choice. */
    private void selectDefaultDropdownChoices(Locator modal) {
        Locator dropdowns = modal.locator("select");
        for (int i = 0; i < dropdowns.count(); i++) {
            Locator dropdown = dropdowns.nth(i);
            String current = dropdown.inputValue();
            if (current.equals("-1") || current.isEmpty()) {
                Locator choices = dropdown.locator("option:not([disabled])");
                if (choices.count() > 0) {
                    dropdown.selectOption(choices.first().getAttribute("value"));
                    page.waitForTimeout(300);
                }
            }
        }
    }

    private void submitBindModal(Locator modal) {
        modal.locator("button", new Locator.LocatorOptions().setHasText("SUBMIT")).click();
        page.waitForTimeout(1000);
        confirmPrompt();
        String message = getAlertMessage();
        closeAlert(message);
        dismissAnyAlert();
        waitUntilBound(10000);
    }

    /**
     * The bind modal closing doesn't guarantee the main account dropdown on the outer form has
     * picked up the newly bound account yet - proceeding straight to submit can land while it's
     * still showing its pre-bind (unbound) state, getting the deposit/withdrawal rejected even
     * though the account really is bound. Wait for isBound() to actually flip before letting a
     * caller continue.
     */
    private void waitUntilBound(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isBound()) {
                return;
            }
            page.waitForTimeout(300);
        }
    }

    /** Dismisses a promo popup if one is covering the page, then any leftover result alert. */
    protected void dismissPromoPopup() {
        Locator popup = page.locator(ALERT_OVERLAY).filter(new Locator.FilterOptions().setHasText("Cancel"));
        try {
            if (popup.count() > 0 && popup.first().isVisible()) {
                popup.getByText("Cancel", new Locator.GetByTextOptions().setExact(true)).click();
                popup.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(5000));
            }
        } catch (Exception ignored) {
        }
        dismissAnyAlert();
    }

    /**
     * Closes a leftover result alert (e.g. "Successfully Created") that can still be covering
     * the page right after a previous action, which would otherwise block the next click.
     */
    protected void dismissAnyAlert() {
        dismissAnyAlert(3000);
    }

    protected void dismissAnyAlert(int timeoutMs) {
        Locator overlay = page.locator(ALERT_OVERLAY);
        try {
            if (overlay.count() == 0 || !overlay.first().isVisible()) {
                return;
            }
            for (String label : new String[] {"Close", "Cancel", "OK", "Confirm"}) {
                Locator button = overlay.getByText(label, new Locator.GetByTextOptions().setExact(true));
                if (button.count() > 0) {
                    button.first().click();
                    break;
                }
            }
            overlay.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(timeoutMs));
        } catch (Exception ignored) {
        }
    }

    public String getAlertMessage() {
        return getAlertMessage(6000);
    }

    public String getAlertMessage(int timeoutMs) {
        page.waitForFunction(
                "(sel) => { const el = document.querySelector(sel); return !!el && el.innerText.trim().length > 0; }",
                ALERT_SELECTOR,
                new Page.WaitForFunctionOptions().setTimeout(timeoutMs));
        return page.locator(ALERT_SELECTOR).first().innerText();
    }

    public void closeAlert(String message) {
        closeAlert(message, 8000);
    }

    /**
     * Closes the alert box that's currently on screen.
     *
     * Targets the actively-shown overlay ({@code ALERT_OVERLAY}) rather than filtering all
     * "...bg__mCPnq" elements by {@code message} text: that filter can silently match zero
     * elements (e.g. when a leftover, already-hidden alert from a previous step is still in the
     * DOM and its text no longer matches), which made waiting for "hidden" succeed immediately
     * against nothing - reporting success while the real alert stayed on screen and blocked
     * every click after it. Also tries several likely button labels, since not every alert uses
     * "Close" (e.g. error alerts like "has been used, please try another").
     */
    public void closeAlert(String message, int timeoutMs) {
        Locator overlay = page.locator(ALERT_OVERLAY);
        try {
            if (overlay.count() > 1) {
                Locator filtered = overlay.filter(new Locator.FilterOptions().setHasText(message));
                if (filtered.count() > 0) {
                    overlay = filtered;
                }
            }
            Locator target = overlay.first();
            for (String label : new String[] {"Close", "OK", "Confirm", "Cancel"}) {
                Locator button = target.getByText(label, new Locator.GetByTextOptions().setExact(true));
                if (button.count() > 0) {
                    button.first().click();
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        overlay.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.HIDDEN).setTimeout(timeoutMs));
    }

    public void confirmPrompt() {
        confirmPrompt(8000);
    }

    public void confirmPrompt(int timeoutMs) {
        page.getByText("Confirm", new Page.GetByTextOptions().setExact(true))
                .click(new Locator.ClickOptions().setTimeout(timeoutMs));
    }

    public void cancelPrompt() {
        cancelPrompt(8000);
    }

    public void cancelPrompt(int timeoutMs) {
        page.getByText("Cancel", new Page.GetByTextOptions().setExact(true))
                .click(new Locator.ClickOptions().setTimeout(timeoutMs));
    }
}
