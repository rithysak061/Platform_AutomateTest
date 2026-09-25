package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Locators ported from the Python Platform_automation project's pages/activationPage.py,
 * verified against the real standalone "Account Activation" form (reachable from the site's top
 * nav, alongside Log In / Register) on https://test-v2.138hk.vip - not the activation modal that
 * appears automatically right after registering (that one's covered by RegisterPage). Both share
 * the same underlying phone-OTP component, though, so this builds on the same BasePage helpers
 * RegisterPage does.
 */
public class ActivationPage extends BasePage {

    /** Returned by {@link #requestCodeResult}: the normal phone-OTP flow. */
    public static final String OTP_ALERT = "otp_alert";
    /** Returned by {@link #requestCodeResult}: the send-your-own-SMS/QR flow. */
    public static final String SERVER_VERIFICATION = "server_verification";

    private static final String PHONE_INPUT = "input[type='tel']";
    private static final String CAPTCHA_INPUT = "input[placeholder='Verification Code']";
    private static final String ALERT_OVERLAY = ".AlertBoard_bg__mCPnq.AlertBoard_show__5duyM";

    public ActivationPage(Page page) {
        super(page);
    }

    public void openActivationModal() {
        dismissPromoPopup();
        Locator trigger = page.getByText("Account Activation", new Page.GetByTextOptions().setExact(true)).first();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                trigger.click(new Locator.ClickOptions().setTimeout(8000));
            } catch (Exception e) {
                dismissPromoPopup();
                trigger.dispatchEvent("click");
            }
            try {
                page.locator(PHONE_INPUT).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(5000));
                return;
            } catch (Exception e) {
                if (attempt == 2) {
                    throw e;
                }
            }
        }
    }

    public void enterPhone(String phone) {
        page.locator(PHONE_INPUT).fill(phone);
    }

    public void enterCaptcha(String captcha) {
        page.locator(CAPTCHA_INPUT).fill(captcha);
    }

    public void requestCode() {
        page.locator("button", new Page.LocatorOptions().setHasText("REQUEST CODE")).first()
                .click(new Locator.ClickOptions().setTimeout(8000));
        page.waitForTimeout(1000);
    }

    /**
     * After clicking REQUEST CODE, figures out what came back: {@link #OTP_ALERT} for the normal
     * flow (a "Message sent" alert that, once closed, reveals the phone-verification-code entry
     * modal), {@link #SERVER_VERIFICATION} for the send-your-own-SMS QR-code flow (China numbers,
     * or any number once anti-abuse throttling switches it to this flow), or the alert's own text
     * for anything else (e.g. a rate-limit or validation error). Returns null if nothing showed
     * up within the timeout.
     */
    public String requestCodeResult(int timeoutMs) {
        Locator overlay = page.locator(ALERT_OVERLAY);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (qrActivationVisible(1)) {
                return SERVER_VERIFICATION;
            }
            if (overlay.count() > 0 && overlay.first().isVisible()) {
                String text = overlay.first().innerText().strip();
                return text.toLowerCase().startsWith("message sent") ? OTP_ALERT : text;
            }
            page.waitForTimeout(200);
        }
        return null;
    }
}
