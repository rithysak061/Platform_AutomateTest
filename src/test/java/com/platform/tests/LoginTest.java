package com.platform.tests;

import com.platform.base.BaseTest;
import com.platform.utility.ConfigReader;
import com.platform.utility.CsvDataReader;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;

/**
 * Automates the "Login" sheet test cases from the shared test case Google Sheet.
 * TC_LOGIN_001, 002, 003, 005, 007, 009 share the same "fill creds + submit +
 * assert result" shape and are data-driven from TestData/Login_TestData.csv.
 * TC_LOGIN_004 (3x wrong password), TC_LOGIN_006 (15x wrong captcha) and
 * TC_LOGIN_008 (Google OAuth login) need distinct flows and are implemented
 * as separate methods below.
 */
public class LoginTest extends BaseTest {

    @DataProvider(name = "loginData")
    public Object[][] loginData() {
        List<String[]> rows = CsvDataReader.read("TestData/Login_TestData.csv");
        Object[][] data = new Object[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            data[i] = rows.get(i);
        }
        return data;
    }

    @Test(dataProvider = "loginData", description = "Verify the Login functionality across valid/invalid username, password and verification code")
    public void verifyLogin(String tcId, String scenario, String username, String password,
                             String captcha, String expectedResult) {
        loginPage.login(username, password, captcha);

        if ("SUCCESS".equals(expectedResult)) {
            Assert.assertTrue(loginPage.isLoggedIn(),
                    tcId + " (" + scenario + "): expected user to be logged in");
        } else {
            String actualMessage = loginPage.getErrorMessage();
            Assert.assertTrue(actualMessage.contains(expectedResult),
                    tcId + " (" + scenario + "): expected error containing [" + expectedResult
                            + "] but got [" + actualMessage + "]");
        }
    }

    @Test(description = "TC_LOGIN_004: Enter invalid password 3 times with a valid username - account should be blocked")
    public void verifyAccountBlockedAfter3InvalidPasswords() {
        String username = "referral2";
        String invalidPassword = "123456789";
        String validCaptcha = ConfigReader.get("captcha.valid");

        for (int attempt = 1; attempt <= 3; attempt++) {
            loginPage.login(username, invalidPassword, validCaptcha);
        }

        String actualMessage = loginPage.getErrorMessage();
        Assert.assertTrue(actualMessage.contains("Wrong password for more than 3 times"),
                "Expected account-blocked message but got [" + actualMessage + "]");
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
        String username = ConfigReader.get("username");
        String password = ConfigReader.get("password");
        String invalidCaptcha = ConfigReader.get("captcha.invalid");

        for (int attempt = 1; attempt <= 15; attempt++) {
            loginPage.login(username, password, invalidCaptcha);
        }

        Assert.assertTrue(loginPage.isCaptchaMasked(), "Expected captcha image to be masked after 15 failed attempts");
        String actualMessage = loginPage.getErrorMessage();
        Assert.assertTrue(actualMessage.contains("Captcha request of out limit"),
                "Expected out-of-limit captcha message but got [" + actualMessage + "]");
    }

    /**
     * TC_LOGIN_008: Login via Google OAuth. Disabled by default - automating a
     * real third-party Google sign-in flow requires a real Google account and
     * is unreliable to script (Google actively blocks automated/headless OAuth
     * flows). Treat this case as a manual test, or wire up a dedicated OAuth
     * test account with Google's testing guidance before enabling.
     */
    @Test(enabled = false,
            description = "TC_LOGIN_008: Verify Login functionality using Google sign-in")
    public void verifyLoginWithGoogle() {
        Assert.fail("Not implemented - Google OAuth login requires a dedicated test account and is out of scope for UI automation. See class-level Javadoc.");
    }
}
