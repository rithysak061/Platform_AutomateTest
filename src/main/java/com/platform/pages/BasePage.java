package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Shared alert/popup handling for page objects that hit the site's generic "AlertBoard"
 * popup/modal system (promo popups, result alerts, confirm/cancel prompts). Ported from the
 * Python Platform_automation project's pages/basePage.py.
 */
public class BasePage {

    // Scoped to the currently-shown overlay ("...show__5duyM"), not just any
    // ".AlertBoard_content__A6ycP" in the DOM: a dismissed alert/prompt can be left behind
    // hidden rather than removed, so an unscoped selector can pick up that stale, invisible
    // node (still with its old text) instead of the new alert that replaced it.
    private static final String ALERT_SELECTOR = ".AlertBoard_bg__mCPnq.AlertBoard_show__5duyM .AlertBoard_content__A6ycP";
    private static final String ALERT_OVERLAY = ".AlertBoard_bg__mCPnq.AlertBoard_show__5duyM";

    protected final Page page;

    protected BasePage(Page page) {
        this.page = page;
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
