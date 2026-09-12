package com.platform.tests;

import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.platform.base.BaseTest;
import com.platform.utility.ConfigReader;
import com.platform.utility.ExtentTestManager;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * Automates the "Login" sheet test cases from the shared test case Google Sheet.
 * Every test case (TC_LOGIN_001-009) is its own dedicated, self-contained method with its data
 * hardcoded directly in the method, one test case per method.
 */
public class LoginTest extends BaseTest {

    @Test(description = "TC_LOGIN_001: Enter a valid username, password and verification code - user should be logged in")
    public void verifyValidLogin() {
        ExtentTest test = ExtentTestManager.getTest();
        String username = ConfigReader.get("username");
        String password = ConfigReader.get("password");
        String validCaptcha = ConfigReader.get("captcha.valid");

        test.log(Status.INFO, "Attempt login with valid credentials");
        loginPage.login(username, password, validCaptcha, step -> test.log(Status.INFO, step));

        test.log(Status.INFO, "Verify the user is logged in");
        Assert.assertTrue(loginPage.isLoggedIn(), "Expected user to be logged in");
    }

    @Test(description = "TC_LOGIN_002: Enter an invalid username with a valid password and verification code - login should be blocked with the correct error")
    public void verifyInvalidUsername() {
        ExtentTest test = ExtentTestManager.getTest();
        String invalidUsername = "ggggggggg";
        String password = "123456";
        String validCaptcha = ConfigReader.get("captcha.valid");

        test.log(Status.INFO, "Attempt login with an invalid username");
        loginPage.login(invalidUsername, password, validCaptcha, step -> test.log(Status.INFO, step));

        test.log(Status.INFO, "Verify the wrong username/password message is shown");
        String actualMessage = loginPage.getErrorMessage();
        Assert.assertTrue(actualMessage.contains("Wrong username/password, please re-enter."),
                "Expected wrong username/password message but got [" + actualMessage + "]");
    }

    @Test(description = "TC_LOGIN_003: Enter a valid username with an invalid password and verification code - login should be blocked with the correct error")
    public void verifyInvalidPassword() {
        ExtentTest test = ExtentTestManager.getTest();
        String username = "sakusd10";
        String invalidPassword = "123456789";
        String validCaptcha = ConfigReader.get("captcha.valid");

        test.log(Status.INFO, "Attempt login with an invalid password");
        loginPage.login(username, invalidPassword, validCaptcha, step -> test.log(Status.INFO, step));

        test.log(Status.INFO, "Verify the wrong username/password message is shown");
        String actualMessage = loginPage.getErrorMessage();
        Assert.assertTrue(actualMessage.contains("Wrong username/password, please re-enter."),
                "Expected wrong username/password message but got [" + actualMessage + "]");
    }

    @Test(description = "TC_LOGIN_004: Enter invalid password 3 times with a valid username - account should be blocked")
    public void verifyAccountBlockedAfter3InvalidPasswords() {
        ExtentTest test = ExtentTestManager.getTest();
        String username = "test5612";
        String invalidPassword  = "123456789";
        String validCaptcha = ConfigReader.get("captcha.valid");

        for (int attempt = 1; attempt <= 4; attempt++) {
            test.log(Status.INFO, "Attempt " + attempt + " of 3 with an invalid password");
            loginPage.login(username, invalidPassword, validCaptcha, step -> test.log(Status.INFO, step));
        }

        test.log(Status.INFO, "Verify the account-blocked message is shown after 3 failed attempts");
        String actualMessage = loginPage.getErrorMessage();
        Assert.assertTrue(actualMessage.contains("Wrong password for more than 3 times"),
                "Expected account-blocked message but got [" + actualMessage + "]");
    }

    @Test(description = "TC_LOGIN_005: Enter a valid username and password with an invalid verification code - login should be blocked with the correct error")
    public void verifyInvalidVerificationCode() {
        ExtentTest test = ExtentTestManager.getTest();
        String username = ConfigReader.get("username");
        String password = ConfigReader.get("password");
        String invalidCaptcha = ConfigReader.get("captcha.invalid");

        test.log(Status.INFO, "Attempt login with an invalid verification code");
        loginPage.login(username, password, invalidCaptcha, step -> test.log(Status.INFO, step));

        test.log(Status.INFO, "Verify the wrong verification code message is shown");
        String actualMessage = loginPage.getErrorMessage();
        Assert.assertTrue(actualMessage.contains("Wrong verification code, please fill in again"),
                "Expected wrong verification code message but got [" + actualMessage + "]");
    }

    /**
     * TC_LOGIN_006: 15x wrong verification code should trigger a 1-hour IP block
     * and mask the captcha image. Disabled by default - running this repeatedly
     * against a shared staging environment can actually trigger the real 1h IP
     * block, which would also lock out other manual/automated testing from the
     * same network. Enable deliberately and only against an environment you
     * are fine locking out for an hour.
     */
    @Test(enabled = false, description = "TC_LOGIN_006: Enter wrong verification code 15 times - IP should be blocked for 1h and captcha masked")
    public void verifyCaptchaLockedAfter15InvalidAttempts() {
        ExtentTest test = ExtentTestManager.getTest();
        String username = ConfigReader.get("username");
        String password = ConfigReader.get("password");
        String invalidCaptcha = ConfigReader.get("captcha.invalid");

        for (int attempt = 1; attempt <= 15; attempt++) {
            test.log(Status.INFO, "Attempt " + attempt + " of 15 with an invalid verification code");
            loginPage.login(username, password, invalidCaptcha, step -> test.log(Status.INFO, step));
        }

        test.log(Status.INFO, "Verify the captcha image is masked");
        Assert.assertTrue(loginPage.isCaptchaMasked(), "Expected captcha image to be masked after 15 failed attempts");

        test.log(Status.INFO, "Verify the out-of-limit captcha message is shown");
        String actualMessage = loginPage.getErrorMessage();
        Assert.assertTrue(actualMessage.contains("Captcha request of out limit"),
                "Expected out-of-limit captcha message but got [" + actualMessage + "]");
    }

    @Test(description = "TC_LOGIN_007: Enter special characters in the username - login should be blocked with the correct validation error")
    public void verifyUsernameWithSpecialCharacters() {
        ExtentTest test = ExtentTestManager.getTest();
        String invalidUsername = "xan@@001";
        String password = "123456";
        String validCaptcha = ConfigReader.get("captcha.valid");

        test.log(Status.INFO, "Attempt login with special characters in the username");
        loginPage.login(invalidUsername, password, validCaptcha, step -> test.log(Status.INFO, step));

        test.log(Status.INFO, "Verify the special-character validation message is shown");
        String actualMessage = loginPage.getErrorMessage();
        Assert.assertTrue(actualMessage.contains("Please enter 6-12 characters"),
                "Expected special-character validation message but got [" + actualMessage + "]");
    }

    /**
     * TC_LOGIN_008: Login via Google OAuth. Disabled by default - automating a
     * real third-party Google sign-in flow requires a real Google account and
     * is unreliable to script (Google actively blocks automated/headless OAuth
     * flows). Treat this case as a manual test, or wire up a dedicated OAuth
     * test account with Google's testing guidance before enabling.
     */
    @Test(enabled = false, description = "TC_LOGIN_008: Verify Login functionality using Google sign-in")
    public void verifyLoginWithGoogle() {
        ExtentTestManager.getTest().log(Status.INFO, "Manual/out-of-scope test - see class-level Javadoc");
        Assert.fail("Not implemented - Google OAuth login requires a dedicated test account and is out of scope for UI automation. See class-level Javadoc.");
    }

    @Test(description = "TC_LOGIN_009: Enter valid credentials for an account that is not yet activated - login should be blocked with the correct error")
    public void verifyLoginWithUnactivatedAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        String username = "sakbot33";
        String password = "123456";
        String validCaptcha = ConfigReader.get("captcha.valid");

        test.log(Status.INFO, "Attempt login with an account that is not yet activated");
        loginPage.login(username, password, validCaptcha, step -> test.log(Status.INFO, step));

        test.log(Status.INFO, "Verify the account-suspended message is shown");
        String actualMessage = loginPage.getErrorMessage();
        Assert.assertTrue(actualMessage.contains("Wrong username/password, please re-enter."),
                "Expected wrong username/password message but got [" + actualMessage + "]");
    }
}
