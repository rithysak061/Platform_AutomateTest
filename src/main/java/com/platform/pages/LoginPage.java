package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.WaitForSelectorState;

import java.util.function.Consumer;

/**
 * Locators below are verified against the real login modal on
 * https://test-v2.138hk.vip. If the site markup changes, re-inspect the modal
 * and adjust the selector strings here - no other class should need to change.
 */
public class LoginPage {

    private final Page page;

    private static final String LOGIN_TRIGGER_BUTTON = "button:has-text('Log In'), a:has-text('Log In')";
    private static final String USERNAME_INPUT = "input[name*='user' i], input[placeholder*='username' i], input[placeholder*='account' i]";
    private static final String PASSWORD_INPUT = "input[type='password']";
    private static final String CAPTCHA_INPUT = "input[name*='captcha' i], input[placeholder*='verification' i], input[placeholder*='code' i]";
    private static final String CAPTCHA_IMAGE = "img[alt*='captcha' i], img[src*='captcha' i]";
    private static final String LOGIN_SUBMIT_BUTTON = "button[class*='Submit' i]:has-text('Log In')";
    private static final String ERROR_MESSAGE = "[class*='error-message' i]:visible, [class*='AlertBoard_content' i]:visible";
    private static final String LOGGED_IN_INDICATOR = "[class*='logout' i], [class*='balance' i], [class*='avatar' i]";
    private static final String ALERT_CLOSE_BUTTON = "[class*='AlertBoard_footer' i] button:has-text('Close')";

    /** The real login endpoint, confirmed by inspecting network traffic against the live site. */
    private static final String LOGIN_API_URL_FRAGMENT = "/api/member/login";

    private String lastApiUrl;
    private String lastApiPayload;
    private String lastApiResponseBody;

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

    /**
     * Clicks submit while capturing the login API exchange (URL, request payload,
     * response body) so a failing test can report exactly what the backend said.
     * If the click never reaches the API (e.g. blocked by client-side validation),
     * the capture is simply left empty.
     */
    public void clickSubmit() {
        lastApiUrl = null;
        lastApiPayload = null;
        lastApiResponseBody = null;
        try {
            Response response = page.waitForResponse(
                    resp -> resp.url().contains(LOGIN_API_URL_FRAGMENT),
                    new Page.WaitForResponseOptions().setTimeout(5000),
                    () -> page.locator(LOGIN_SUBMIT_BUTTON).first().click());
            lastApiUrl = response.url();
            lastApiPayload = response.request().postData();
            lastApiResponseBody = safeReadBody(response);
        } catch (TimeoutError e) {
            // The click already happened inside the callback above; the API just never
            // fired within the wait window (e.g. client-side validation short-circuited it).
        }
        waitForLoginResult();
    }

    private String safeReadBody(Response response) {
        try {
            return response.text();
        } catch (Exception e) {
            return "(unable to read response body: " + e.getMessage() + ")";
        }
    }

    /**
     * Waits for the async login response to render (error popup/inline validation
     * or the logged-in header) before returning, so callers - including repeated
     * attempts in the same session, e.g. testing account lockout - never act while
     * the previous attempt's result is still in flight.
     */
    private void waitForLoginResult() {
        Locator result = page.locator(ERROR_MESSAGE).or(page.locator(LOGGED_IN_INDICATOR));
        try {
            result.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        } catch (TimeoutError e) {
            // No explicit signal appeared in time; let the caller's own assertions surface this.
        }
    }

    public void login(String username, String password, String captcha) {
        login(username, password, captcha, step -> { });
    }

    /**
     * Same as {@link #login(String, String, String)} but reports each step it takes
     * through the given callback, so a caller can log them (e.g. into a test report)
     * without this page object needing to know anything about a reporting library.
     */
    public void login(String username, String password, String captcha, Consumer<String> stepLogger) {
        if (dismissAlertIfPresent()) {
            stepLogger.accept("Dismissed a leftover alert from a previous attempt");
        }
        if (!isLoginFormOpen()) {
            stepLogger.accept("Open the login form");
            clickLoginTrigger();
        }
        stepLogger.accept("Enter username: " + username);
        enterUsername(username);
        stepLogger.accept("Enter password");
        enterPassword(password);
        stepLogger.accept("Enter verification code: " + captcha);
        enterCaptcha(captcha);
        stepLogger.accept("Click the Log In submit button");
        clickSubmit();
    }

    /**
     * A prior failed attempt in the same session can leave the error popup open,
     * which overlays and blocks clicks on the rest of the page - close it first
     * so repeated login attempts (e.g. testing account lockout) can proceed.
     */
    private boolean dismissAlertIfPresent() {
        Locator closeButton = page.locator(ALERT_CLOSE_BUTTON);
        if (closeButton.count() > 0 && closeButton.first().isVisible()) {
            closeButton.first().click();
            return true;
        }
        return false;
    }

    /**
     * The login modal stays open across repeated attempts in the same session
     * (e.g. testing account lockout); clicking the header trigger again while it
     * is still open would hit the modal's own overlay instead.
     */
    private boolean isLoginFormOpen() {
        Locator input = page.locator(USERNAME_INPUT);
        return input.count() > 0 && input.first().isVisible();
    }

    public String getErrorMessage() {
        Locator error = page.locator(ERROR_MESSAGE).first();
        error.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
        return error.textContent().trim();
    }

    public boolean isLoggedIn() {
        try {
            page.locator(LOGGED_IN_INDICATOR).first()
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE));
            return true;
        } catch (TimeoutError e) {
            return false;
        }
    }

    public boolean isCaptchaMasked() {
        String src = page.locator(CAPTCHA_IMAGE).first().getAttribute("src");
        return src != null && src.contains("****");
    }

    public String getLastApiUrl() {
        return lastApiUrl;
    }

    public String getLastApiPayload() {
        return lastApiPayload;
    }

    public String getLastApiResponseBody() {
        return lastApiResponseBody;
    }
}
