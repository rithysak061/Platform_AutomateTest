package com.platform.tests;

import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.platform.base.BaseTest;
import com.platform.utility.ConfigReader;
import com.platform.utility.CsvDataReader;
import com.platform.utility.ExtentTestManager;
import org.testng.Assert;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Automates the "Login" sheet test cases from the shared test case Google Sheet.
 * TC_LOGIN_001 (the only SUCCESS row) is verified by {@link #verifyValidLogin}.
 * TC_LOGIN_002, 003, 005, 007, 009 share the same "fill creds + submit + assert
 * error message" shape and are verified by {@link #verifyInvalidLogin}; both are
 * data-driven from TestData/Login_TestData.csv.
 * TC_LOGIN_004 (3x wrong password), TC_LOGIN_006 (15x wrong captcha) and
 * TC_LOGIN_008 (Google OAuth login) need distinct flows and are implemented
 * as separate methods below.
 */
public class LoginTest extends BaseTest {

    @DataProvider(name = "validLoginData")
    public Object[][] validLoginData() {
        return filteredRows(row -> "SUCCESS".equals(row[5]));
    }

    @DataProvider(name = "invalidLoginData")
    public Object[][] invalidLoginData() {
        return filteredRows(row -> !"SUCCESS".equals(row[5]));
    }

    private Object[][] filteredRows(Predicate<String[]> expectedResultFilter) {
        List<String[]> rows = CsvDataReader.read("TestData/Login_TestData.csv").stream()
                .filter(expectedResultFilter)
                .collect(Collectors.toList());

        String testCaseId = System.getProperty("testCaseId");
        if (testCaseId != null && !testCaseId.isBlank()) {
            rows = rows.stream()
                    .filter(row -> row[0].equalsIgnoreCase(testCaseId))
                    .collect(Collectors.toList());
        }

        Object[][] data = new Object[rows.size()][];
        for (int i = 0; i < rows.size(); i++) {
            data[i] = rows.get(i);
        }
        return data;
    }

    @Test(dataProvider = "validLoginData", description = "Verify successful login with a valid username, password and verification code")
    public void verifyValidLogin(String tcId, String scenario, String username, String password,
                                  String captcha, String expectedResult) {
        ExtentTest test = ExtentTestManager.getTest();
        test.log(Status.INFO, tcId + ": " + scenario);

        loginPage.login(username, password, captcha, step -> test.log(Status.INFO, step));

        test.log(Status.INFO, "Verify the user is logged in");
        Assert.assertTrue(loginPage.isLoggedIn(),
                tcId + " (" + scenario + "): expected user to be logged in");
    }

    @Test(dataProvider = "invalidLoginData", description = "Verify the correct error message for an invalid username, password or verification code")
    public void verifyInvalidLogin(String tcId, String scenario, String username, String password,
                                    String captcha, String expectedResult) {
        ExtentTest test = ExtentTestManager.getTest();
        test.log(Status.INFO, tcId + ": " + scenario);

        loginPage.login(username, password, captcha, step -> test.log(Status.INFO, step));

        test.log(Status.INFO, "Verify the error message contains: " + expectedResult);
        String actualMessage = loginPage.getErrorMessage();
        Assert.assertTrue(actualMessage.contains(expectedResult),
                tcId + " (" + scenario + "): expected error containing [" + expectedResult
                        + "] but got [" + actualMessage + "]");
    }

    @Test(description = "TC_LOGIN_004: Enter invalid password 3 times with a valid username - account should be blocked")
    public void verifyAccountBlockedAfter3InvalidPasswords() {
        ExtentTest test = ExtentTestManager.getTest();
        String username = "referral2";
        String invalidPassword = "123456789";
        String validCaptcha = ConfigReader.get("captcha.valid");

        for (int attempt = 1; attempt <= 3; attempt++) {
            test.log(Status.INFO, "Attempt " + attempt + " of 3 with an invalid password");
            loginPage.login(username, invalidPassword, validCaptcha, step -> test.log(Status.INFO, step));
        }

        test.log(Status.INFO, "Verify the account-blocked message is shown after 3 failed attempts");
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
}
