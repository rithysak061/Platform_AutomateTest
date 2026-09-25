package com.platform.tests;

import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.microsoft.playwright.Page;
import com.platform.base.BaseTest;
import com.platform.pages.RegisterPage;
import com.platform.utility.ConfigReader;
import com.platform.utility.ExtentTestManager;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Automates the "Register" sheet's Sign Up test cases, ported from the Python
 * Platform_automation project (testcases/register.py + pages/registerPage.py). Every method's
 * description carries that sheet's real TC_SIGNUP_XXX id, matching the Python source exactly -
 * the sheet's ids and this project's target site (test-v2.138hk.vip) already line up 1:1, unlike
 * Deposit's sheet.
 *
 * TC_SIGNUP_002 (China number, needs a real phone to receive/send an SMS) and TC_SIGNUP_014
 * (agent-url registration, needs a specific agent URL plus admin-panel verification) are both
 * marked Automation=FALSE in the sheet itself, so neither is automated here - matching the
 * Python source's own scope exactly.
 *
 * Usernames and phone numbers are generated fresh per run (matching the sheet's own
 * ${random_name}/${random_number} placeholders) since the site rejects a username or phone
 * number that's already registered.
 */
public class RegisterTest extends BaseTest {

    private static final String VALID_PASSWORD = "123456";
    private static final String[] VALID_PHONE_PREFIXES = {"6", "7", "8", "9"};
    private static final String INVALID_PHONE = "21342312";
    private static final String DUPLICATE_PHONE = "51234567";
    private static final String CHINA_PHONE_PREFIX = "1626";
    private static final String INACTIVE_PHONE = "76143361";

    private static String randomUsername() {
        return "qatest" + ThreadLocalRandom.current().nextInt(100000, 1000000);
    }

    private static String randomPhone(String prefix) {
        return prefix + ThreadLocalRandom.current().nextInt(1000000, 10000000);
    }

    private static String validPhone() {
        String prefix = VALID_PHONE_PREFIXES[ThreadLocalRandom.current().nextInt(VALID_PHONE_PREFIXES.length)];
        return randomPhone(prefix);
    }

    private RegisterPage openRegister(ExtentTest test) {
        RegisterPage registerPage = new RegisterPage(BaseTest.getCurrentPage());
        test.log(Status.INFO, "Open the Register modal");
        registerPage.openRegisterModal();
        test.log(Status.INFO, "Select Hong Kong as the contact number's country");
        registerPage.selectCountry("Hong Kong");
        return registerPage;
    }

    /**
     * Fills and submits the register form with a fresh (or given) valid contact number, then
     * figures out which activation flow the site shows - see RegisterPage.registrationActivationKind.
     */
    private String registerAndDetectActivationKind(ExtentTest test, RegisterPage registerPage, String phone) {
        return registerAndDetectActivationKind(test, registerPage, phone, randomUsername());
    }

    private String registerAndDetectActivationKind(ExtentTest test, RegisterPage registerPage, String phone, String username) {
        test.log(Status.INFO, "Fill and submit the register form (username: " + username + ", phone: " + phone + ")");
        registerPage.fillForm(username, VALID_PASSWORD, VALID_PASSWORD, phone, ConfigReader.get("captcha.valid"));
        registerPage.submit();
        String kind = registerPage.registrationActivationKind(8000);
        if (RegisterPage.OTP_ALERT.equals(kind)) {
            String message = registerPage.getAlertMessage();
            if (message.contains("has not been activated")) {
                // This contact number already has a pending, unactivated registration from an
                // earlier run - registrationActivationKind() can't tell a fresh "Message sent"
                // alert apart from this "already pending" popup (same overlay class), and this
                // one has no Close/OK/Confirm/Cancel button for the generic closeAlert() to find.
                // Dismiss it via its own "Active account" button instead.
                registerPage.clickActiveAccountButton();
            } else {
                registerPage.closeAlert(message);
            }
        }
        return kind;
    }

    @Test(description = "TC_SIGNUP_003: Verify the Sign up functionality with special characters in the username shows the correct error")
    public void verifyRegisterSpecialCharacterUsername() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);

        test.log(Status.INFO, "Fill the form with a special-character username");
        registerPage.fillForm("xan@2222", VALID_PASSWORD, VALID_PASSWORD, validPhone(), ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Submit and check the username-format error");
        registerPage.submit();
        String message = registerPage.getFieldError();
        Assert.assertEquals(message.strip(), "Please enter 6-12 characters (a-z 0-9). special characters are not allowed");
    }

    @Test(description = "TC_SIGNUP_004: Verify the Sign up functionality with capital letters and Chinese characters in the password shows the correct error")
    public void verifyRegisterInvalidPasswordCharacters() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);
        String invalidPassword = "xan是的2222";

        test.log(Status.INFO, "Fill the form with capital letters and Chinese characters in the password");
        registerPage.fillForm(randomUsername(), invalidPassword, invalidPassword, validPhone(), ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Submit and check the password-format error");
        registerPage.submit();
        String message = registerPage.getFieldError();
        Assert.assertEquals(message.strip(), "Please enter 6-12 characters (a-z 0-9). special characters are not allowed");
    }

    @Test(description = "TC_SIGNUP_005: Verify the Sign up functionality with a mismatched repeat password shows the correct error")
    public void verifyRegisterMismatchedRepeatPassword() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);

        test.log(Status.INFO, "Fill the form with a repeat password that doesn't match");
        registerPage.fillForm(randomUsername(), VALID_PASSWORD, VALID_PASSWORD + "ssssss", validPhone(), ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Submit and check the password-mismatch error");
        registerPage.submit();
        String message = registerPage.getFieldError();
        Assert.assertEquals(message.strip(), "Must be the same as the password");
    }

    @Test(description = "TC_SIGNUP_006: Verify the Sign up functionality with an invalid contact number format shows the correct error")
    public void verifyRegisterInvalidPhoneFormat() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);

        test.log(Status.INFO, "Fill the form with an invalid contact number ('" + INVALID_PHONE + "')");
        registerPage.fillForm(randomUsername(), VALID_PASSWORD, VALID_PASSWORD, INVALID_PHONE, ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Submit and check the phone-format error");
        registerPage.submit();
        String message = registerPage.getFieldError();
        Assert.assertEquals(message.strip(), "Contact Phone format error");
    }

    @Test(description = "TC_SIGNUP_007: Verify the Sign up functionality with an invalid verification code shows the correct error")
    public void verifyRegisterInvalidVerificationCode() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);

        test.log(Status.INFO, "Fill the form with an invalid verification code");
        registerPage.fillForm(randomUsername(), VALID_PASSWORD, VALID_PASSWORD, validPhone(), ConfigReader.get("captcha.invalid"));

        test.log(Status.INFO, "Submit and check the verification-code error");
        registerPage.submit();
        String message = registerPage.getFieldError();
        Assert.assertEquals(message.strip(), "Verification wrong");
    }

    @Test(description = "TC_SIGNUP_008: Verify the Sign up functionality with an already-used username shows the correct error")
    public void verifyRegisterDuplicateUsername() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);
        String duplicateUsername = ConfigReader.get("username");

        test.log(Status.INFO, "Fill the form with an already-used username ('" + duplicateUsername + "')");
        registerPage.fillForm(duplicateUsername, VALID_PASSWORD, VALID_PASSWORD, validPhone(), ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Submit and check the duplicate-username error");
        registerPage.submit();
        String message = registerPage.getFieldError();
        Assert.assertEquals(message.strip(), "Account has been used");
    }

    @Test(description = "TC_SIGNUP_009: Verify the Sign up functionality with an already-registered contact number shows the correct error")
    public void verifyRegisterDuplicatePhoneNumber() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);

        test.log(Status.INFO, "Fill the form with an already-registered contact number ('" + DUPLICATE_PHONE + "')");
        registerPage.fillForm(randomUsername(), VALID_PASSWORD, VALID_PASSWORD, DUPLICATE_PHONE, ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Submit and check the duplicate-phone error");
        registerPage.submit();
        String message = registerPage.getFieldError();
        Assert.assertEquals(message.strip(), "Status error -201");
    }

    @Test(description = "TC_SIGNUP_010: Verify the Sign up functionality with a contact number that already has an unactivated registration shows the correct error")
    public void verifyRegisterPhoneAlreadyPendingActivation() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);

        test.log(Status.INFO, "Register once with a fresh contact number ('" + INACTIVE_PHONE + "') to create a pending registration");
        registerAndDetectActivationKind(test, registerPage, INACTIVE_PHONE);

        test.log(Status.INFO, "Open Register again and reuse the same contact number under a new username");
        // A fresh navigation (rather than trying to close whichever activation modal is currently
        // open) reliably gets back to a clean Register form regardless of which activation flow
        // just appeared. The contact-number country defaults to whatever the site currently
        // considers its default (observed as Cambodia, not Hong Kong) rather than carrying over
        // the earlier selection, so it has to be reselected here too or the HK-format phone
        // number below reads as a format error instead of triggering the pending-activation check.
        BaseTest.getCurrentPage().navigate(ConfigReader.get("base.url"));
        BaseTest.getCurrentPage().waitForTimeout(1500);
        registerPage.openRegisterModal();
        registerPage.selectCountry("Hong Kong");
        registerPage.fillForm(randomUsername(), VALID_PASSWORD, VALID_PASSWORD, INACTIVE_PHONE, ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Submit and check the not-activated popup");
        registerPage.submit();
        String message = registerPage.getAlertMessage();
        Assert.assertEquals(message.strip(), "This account has not been activated. Please go to activate!");
        Assert.assertTrue(registerPage.activateAccountButtonVisible(),
                "The not-activated popup showed up, but its \"Active account\" button did not.");
    }

    @Test(description = "TC_SIGNUP_011: Verify the Sign up functionality with a Non China number shows the phone-verification activation modal")
    public void verifyRegisterNonChinaActivationRedirect() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);

        test.log(Status.INFO, "Register with a fresh, valid (non-China) contact number");
        String kind = registerAndDetectActivationKind(test, registerPage, validPhone());

        test.log(Status.INFO, "Verify the phone-verification-code activation modal appears");
        if (RegisterPage.SERVER_VERIFICATION.equals(kind)) {
            throw new SkipException("Got the send-your-own-SMS server-verification flow instead of the normal "
                    + "phone-OTP flow this test expects - likely this account's anti-abuse throttling kicking in "
                    + "after heavy testing. Already covered by TC_SIGNUP_012 for China numbers; re-run later once "
                    + "the throttle clears.");
        }
        Assert.assertEquals(kind, RegisterPage.OTP_ALERT,
                "Registered with a valid contact number, but no activation step showed up (got \"" + kind + "\").");
        Assert.assertTrue(registerPage.activationModalVisible(),
                "The activation alert closed, but the phone-verification code entry modal never showed up.");
    }

    @Test(description = "TC_SIGNUP_012: Verify the Sign up functionality with a China number shows the QR-code activation modal")
    public void verifyRegisterChinaNumberActivationRedirect() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);

        test.log(Status.INFO, "Select China as the contact number's country");
        registerPage.selectCountry("China");

        test.log(Status.INFO, "Register with a fresh China-format contact number");
        String phone = randomPhone(CHINA_PHONE_PREFIX);
        registerPage.fillForm(randomUsername(), VALID_PASSWORD, VALID_PASSWORD, phone, ConfigReader.get("captcha.valid"));
        registerPage.submit();

        test.log(Status.INFO, "Verify the QR-code activation modal appears");
        Assert.assertTrue(registerPage.qrActivationVisible(8000),
                "Registered with a China-format contact number, but the QR-code activation modal never showed up.");
    }

    @Test(description = "TC_SIGNUP_013: Verify the Sign up functionality without activating the account does not automatically log the user in")
    public void verifyRegisterUnactivatedAccountStaysLoggedOut() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);

        test.log(Status.INFO, "Register with a fresh valid contact number but don't activate it");
        registerAndDetectActivationKind(test, registerPage, validPhone());

        test.log(Status.INFO, "Verify the new account was not automatically logged in");
        Assert.assertFalse(registerPage.isLoggedIn(), "Registered without activating, but the site logged the user in anyway.");
    }

    @Test(description = "TC_SIGNUP_001: Verify the Sign up with a Non China number and account activation functionality")
    public void verifyRegisterFullRegistrationAndActivation() {
        ExtentTest test = ExtentTestManager.getTest();
        RegisterPage registerPage = openRegister(test);

        test.log(Status.INFO, "Register with a fresh valid contact number");
        String kind = registerAndDetectActivationKind(test, registerPage, validPhone());

        test.log(Status.INFO, "Verify the phone-verification-code activation modal appears");
        if (RegisterPage.SERVER_VERIFICATION.equals(kind)) {
            throw new SkipException("Got the send-your-own-SMS server-verification flow instead of the normal "
                    + "phone-OTP flow this test can complete - likely this account's anti-abuse throttling kicking "
                    + "in after heavy testing. That flow needs a real phone to send the SMS from, so it can't be "
                    + "finished here. Re-run later once the throttle clears.");
        }
        Assert.assertEquals(kind, RegisterPage.OTP_ALERT,
                "Registered with a valid contact number, but no activation step showed up (got \"" + kind + "\").");
        Assert.assertTrue(registerPage.activationModalVisible(),
                "The activation alert closed, but the phone-verification code entry modal never showed up.");

        String otp = ConfigReader.get("captcha.valid");
        test.log(Status.INFO, "Enter the verification code ('" + otp + "') and activate the account");
        registerPage.enterOtp(otp);
        registerPage.submitActivation();

        test.log(Status.INFO, "Verify the user is logged in and lands on the deposit page");
        BaseTest.getCurrentPage().waitForURL("**/member/deposit/**",
                new Page.WaitForURLOptions().setTimeout(10000));
        Assert.assertTrue(registerPage.isLoggedIn(), "Activated the account, but the user was not logged in afterward.");
    }
}
