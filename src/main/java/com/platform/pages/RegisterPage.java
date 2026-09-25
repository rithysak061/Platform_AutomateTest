package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Locators ported from the Python Platform_automation project's pages/registerPage.py, verified
 * against the real Register modal on https://test-v2.138hk.vip.
 */
public class RegisterPage extends BasePage {

    /** Returned by {@link #registrationActivationKind}: the normal phone-OTP flow. */
    public static final String OTP_ALERT = "otp_alert";
    /** Returned by {@link #registrationActivationKind}: the send-your-own-SMS/QR flow. */
    public static final String SERVER_VERIFICATION = "server_verification";

    private static final String USERNAME_INPUT = "input[name='account']";
    private static final String PASSWORD_INPUT = "input[name='password']";
    private static final String REPEAT_PASSWORD_INPUT = "input[name='re-enter-password']";
    private static final String PHONE_INPUT = "input[type='tel']";
    private static final String CAPTCHA_INPUT = "input[placeholder='Verification Code']";
    private static final String SUBMIT_BUTTON = "button.SubmitButton_submit__luKqw";
    private static final String ALERT_OVERLAY = ".AlertBoard_bg__mCPnq.AlertBoard_show__5duyM";

    public RegisterPage(Page page) {
        super(page);
    }

    /**
     * Opens the Register modal. A leftover "hide-modal" backdrop layer from a previous popup can
     * sit on top of the page with pointer-events still enabled even though it's visually empty,
     * silently swallowing a normal click on the Register button - dispatchEvent fires the click
     * straight at the button's own DOM node instead of doing a real, position-based mouse click,
     * so it isn't affected by whatever invisible layer is sitting on top of it.
     */
    public void openRegisterModal() {
        dismissPromoPopup();
        Locator trigger = page.getByText("Register", new Page.GetByTextOptions().setExact(true)).first();
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                trigger.click(new Locator.ClickOptions().setTimeout(8000));
            } catch (Exception e) {
                dismissPromoPopup();
                trigger.dispatchEvent("click");
            }
            try {
                page.locator(USERNAME_INPUT).waitFor(new Locator.WaitForOptions()
                        .setState(WaitForSelectorState.VISIBLE).setTimeout(5000));
                return;
            } catch (Exception e) {
                if (attempt == 2) {
                    throw e;
                }
            }
        }
    }

    public void enterUsername(String username) {
        page.locator(USERNAME_INPUT).fill(username);
    }

    public void enterPassword(String password) {
        page.locator(PASSWORD_INPUT).fill(password);
    }

    public void enterRepeatPassword(String repeatPassword) {
        page.locator(REPEAT_PASSWORD_INPUT).fill(repeatPassword);
    }

    public void enterPhone(String phone) {
        page.locator(PHONE_INPUT).fill(phone);
    }

    public void enterCaptcha(String captcha) {
        page.locator(CAPTCHA_INPUT).fill(captcha);
    }

    public void fillForm(String username, String password, String repeatPassword, String phone, String captcha) {
        enterUsername(username);
        enterPassword(password);
        enterRepeatPassword(repeatPassword);
        enterPhone(phone);
        enterCaptcha(captcha);
    }

    public void submit() {
        page.locator("#main-modal").last().locator(SUBMIT_BUTTON).first().click(new Locator.ClickOptions().setTimeout(8000));
        page.waitForTimeout(1000);
    }

    /**
     * After submitting a valid registration, figures out which activation flow the site is
     * presenting: {@link #OTP_ALERT} for the normal flow (a "Message sent" alert that, once
     * closed, reveals a 4-digit phone-verification-code entry modal), or
     * {@link #SERVER_VERIFICATION} when it goes straight to a send-this-code-from-your-phone /
     * QR-code screen instead - the site's normal path for China numbers, and also what a heavily
     * automated account can get switched to for any number once its anti-abuse throttling kicks
     * in (that path can't be completed here, since it needs a real phone to send the SMS from).
     * Returns null if neither showed up within the timeout.
     */
    public String registrationActivationKind(int timeoutMs) {
        Locator overlay = page.locator(ALERT_OVERLAY);
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (qrActivationVisible(1)) {
                return SERVER_VERIFICATION;
            }
            if (overlay.count() > 0 && overlay.first().isVisible()) {
                return OTP_ALERT;
            }
            page.waitForTimeout(200);
        }
        return null;
    }

    /** True once the phone-OTP "Activate Account" modal (non-China numbers) is showing. */
    public boolean activationModalVisible() {
        return phoneOtpModalVisible();
    }

    public boolean activateAccountButtonVisible() {
        return activateAccountButtonVisible(5000);
    }

    public boolean activateAccountButtonVisible(int timeoutMs) {
        try {
            page.getByText("Active account", new Page.GetByTextOptions().setExact(true))
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void clickActiveAccountButton() {
        page.getByText("Active account", new Page.GetByTextOptions().setExact(true))
                .click(new Locator.ClickOptions().setTimeout(8000));
        page.waitForTimeout(1000);
    }
}
