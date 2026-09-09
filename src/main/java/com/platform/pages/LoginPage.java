package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;

/**
 * Locators below are best-guess, resilient selectors (role/placeholder/text based)
 * since the live DOM could not be inspected when this was written. Verify each
 * against the real login modal on https://test-v2.138hk.vip and adjust the
 * selector strings here if the site uses different markup - no other class
 * should need to change.
 */
public class LoginPage {

    private final Page page;

    private static final String LOGIN_TRIGGER_BUTTON = "button:has-text('Log In'), a:has-text('Log In')";
    private static final String USERNAME_INPUT = "input[name*='user' i], input[placeholder*='username' i], input[placeholder*='account' i]";
    private static final String PASSWORD_INPUT = "input[type='password']";
    private static final String CAPTCHA_INPUT = "input[name*='captcha' i], input[placeholder*='verification' i], input[placeholder*='code' i]";
    private static final String CAPTCHA_IMAGE = "img[alt*='captcha' i], img[src*='captcha' i]";
    private static final String LOGIN_SUBMIT_BUTTON = "button[type='submit']:has-text('Log'), button:has-text('Login')";
    private static final String ERROR_MESSAGE = "[class*='error' i], [class*='explain' i], [role='alert']";
    private static final String LOGGED_IN_INDICATOR = "[class*='balance' i], [class*='avatar' i], button:has-text('Log Out'), button:has-text('Logout')";

    public LoginPage(Page page) {
        this.page = page;
    }

    public void open(String baseUrl) {
        page.navigate(baseUrl);
    }

    public void clickLoginTrigger() {
        page.locator(LOGIN_TRIGGER_BUTTON).first().click();
    }

    public void enterUsername(String username) {
        Locator input = page.locator(USERNAME_INPUT).first();
        input.click();
        input.fill(username);
    }

    public void enterPassword(String password) {
        Locator input = page.locator(PASSWORD_INPUT).first();
        input.click();
        input.fill(password);
    }

    public void enterCaptcha(String captcha) {
        Locator input = page.locator(CAPTCHA_INPUT).first();
        input.click();
        input.fill(captcha);
    }

    public void clickSubmit() {
        page.locator(LOGIN_SUBMIT_BUTTON).first().click();
    }

    public void login(String username, String password, String captcha) {
        clickLoginTrigger();
        enterUsername(username);
        enterPassword(password);
        enterCaptcha(captcha);
        clickSubmit();
    }

    public String getErrorMessage() {
        Locator error = page.locator(ERROR_MESSAGE).first();
        error.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return error.textContent().trim();
    }

    public boolean isLoggedIn() {
        return page.locator(LOGGED_IN_INDICATOR).first().isVisible();
    }

    public boolean isCaptchaMasked() {
        String src = page.locator(CAPTCHA_IMAGE).first().getAttribute("src");
        return src != null && src.contains("****");
    }
}
