package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Locators ported from the Python Platform_automation project's pages/forgotPasswordPage.py,
 * verified against the real Forgot Password form on https://test-v2.138hk.vip.
 */
public class ForgotPasswordPage extends BasePage {

    /** Returned by {@link #requestCodeResult}: the normal phone-OTP flow. */
    public static final String OTP_ALERT = "otp_alert";
    /** Returned by {@link #requestCodeResult}: the send-your-own-SMS/QR flow. */
    public static final String SERVER_VERIFICATION = "server_verification";

    private static final String PHONE_INPUT = "input[type='tel']";
    private static final String CAPTCHA_INPUT = "input[placeholder='Verification Code']";
    private static final String NEW_PASSWORD_INPUT = "input[name='new-password']";
    private static final String CONFIRM_PASSWORD_INPUT = "input[name='confirm-password']";
    private static final String ALERT_OVERLAY = ".AlertBoard_bg__mCPnq.AlertBoard_show__5duyM";

    public ForgotPasswordPage(Page page) {
        super(page);
    }

    public void openForgotPasswordModal() {
        dismissPromoPopup();
        Locator loginTrigger = page.getByText("Log In", new Page.GetByTextOptions().setExact(true)).first();
        Locator forgotPasswordLink = page.getByText("Forgot Password?", new Page.GetByTextOptions().setExact(true));
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                loginTrigger.click(new Locator.ClickOptions().setTimeout(8000));
            } catch (Exception e) {
                dismissPromoPopup();
                loginTrigger.dispatchEvent("click");
            }
            try {
                forgotPasswordLink.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(5000));
                break;
            } catch (Exception e) {
                if (attempt == 2) {
                    throw e;
                }
            }
        }
        forgotPasswordLink.click();
        page.locator(PHONE_INPUT).waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(5000));
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

    /** Fills the "Set New Password" step that follows a successful phone-OTP submit. */
    public void enterNewPassword(String password, String repeatPassword) {
        page.locator(NEW_PASSWORD_INPUT).fill(password);
        page.locator(CONFIRM_PASSWORD_INPUT).fill(repeatPassword);
    }

    /**
     * Clicks whichever "SUBMIT" button is currently on screen - the same label is reused for the
     * phone-OTP step and the new-password step, so this is safe to call at either point in the
     * flow. Overrides BasePage.submit() (which targets the deposit/withdraw amount form's submit
     * button instead) since this flow never has that button on screen.
     */
    @Override
    public void submit() {
        page.locator("button", new Page.LocatorOptions().setHasText("SUBMIT")).first()
                .click(new Locator.ClickOptions().setTimeout(8000));
        page.waitForTimeout(1000);
    }
}
