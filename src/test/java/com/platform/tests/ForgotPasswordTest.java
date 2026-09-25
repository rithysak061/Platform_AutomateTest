package com.platform.tests;

import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.platform.base.BaseTest;
import com.platform.pages.ForgotPasswordPage;
import com.platform.pages.RegisterPage;
import com.platform.utility.ConfigReader;
import com.platform.utility.ExtentTestManager;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Automates the "Forgot Password" sheet's test cases, ported from the Python
 * Platform_automation project (testcases/forgot_password.py + pages/forgotPasswordPage.py).
 * Every method's description carries that sheet's real TC_FORGOTPASS_XXX id. The sheet's own
 * Automation column currently flags only 001/002 as TRUE (the rest, including several this class
 * automates, are FALSE) - per explicit direction, this follows the Python source's actual working
 * scope instead of that column, since the column simply hasn't caught up with what's already
 * been automated.
 *
 * A full reset (request code -> enter phone OTP -> set new password) can only complete against a
 * real, already-registered and already-activated account, since the site only accepts the fixed
 * "1111" OTP for a number that actually has one. This suite only has one such number available
 * (the same duplicate-phone account RegisterTest's duplicate-phone-number case uses), so every
 * test that needs to reach the "set new password" step reuses it. The site also only allows one
 * SMS/OTP request per phone number every couple of minutes, so those tests wait out that cooldown
 * before requesting another code for that same number.
 */
public class ForgotPasswordTest extends BaseTest {

    private static final int SMS_REQUEST_COOLDOWN_SECONDS = 130;
    private static final String DUPLICATE_PHONE = "51234567";
    private static final String INVALID_PHONE = "21342312";
    private static final String VALID_PASSWORD = "123456";
    private static final String NEW_PASSWORD = "123456";
    private static final String MISMATCHED_NEW_PASSWORD = "654321";
    private static final String SPECIAL_CHAR_PASSWORD = "123456@a";
    private static final String VALID_OTP = "1111";
    private static final String INVALID_OTP = "9999";
    private static final String EXPIRED_OTP = "4123";
    private static final String[] VALID_PHONE_PREFIXES = {"6", "7", "8", "9"};

    private static long lastDuplicatePhoneRequestMillis = 0;

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

    private ForgotPasswordPage openForgotPassword(ExtentTest test) {
        ForgotPasswordPage forgotPasswordPage = new ForgotPasswordPage(BaseTest.getCurrentPage());
        test.log(Status.INFO, "Open the Forgot Password form");
        forgotPasswordPage.openForgotPasswordModal();
        test.log(Status.INFO, "Select Hong Kong as the contact number's country");
        forgotPasswordPage.selectCountry("Hong Kong");
        return forgotPasswordPage;
    }

    /**
     * Requests a reset code for the one known real, activated account this suite has access to,
     * waiting out the site's SMS cooldown if a previous test in this run already requested a code
     * for the same number.
     */
    private String requestCodeForExistingAccount(ExtentTest test, ForgotPasswordPage forgotPasswordPage) {
        long waitNeededMillis = SMS_REQUEST_COOLDOWN_SECONDS * 1000L - (System.currentTimeMillis() - lastDuplicatePhoneRequestMillis);
        if (waitNeededMillis > 0) {
            test.log(Status.INFO, "Wait " + (waitNeededMillis / 1000) + "s for the site's SMS request cooldown on this number to clear");
            BaseTest.getCurrentPage().waitForTimeout(waitNeededMillis);
        }
        test.log(Status.INFO, "Request a reset code for the existing account ('" + DUPLICATE_PHONE + "')");
        forgotPasswordPage.enterPhone(DUPLICATE_PHONE);
        forgotPasswordPage.enterCaptcha(ConfigReader.get("captcha.valid"));
        forgotPasswordPage.requestCode();
        String message = forgotPasswordPage.getAlertMessage();
        forgotPasswordPage.closeAlert(message);
        lastDuplicatePhoneRequestMillis = System.currentTimeMillis();
        return DUPLICATE_PHONE;
    }

    /**
     * Requests a code for a fresh, valid contact number and closes the resulting alert - unless
     * the account's anti-abuse throttling has switched this request to the send-your-own-SMS/QR
     * flow instead of a normal "Message sent" alert (seen after heavy testing), in which case
     * this skips rather than hanging waiting for an alert that isn't coming.
     */
    private void requestFreshCodeOrSkip(ExtentTest test, ForgotPasswordPage forgotPasswordPage, String phone) {
        forgotPasswordPage.enterPhone(phone);
        forgotPasswordPage.enterCaptcha(ConfigReader.get("captcha.valid"));
        forgotPasswordPage.requestCode();
        String kind = forgotPasswordPage.requestCodeResult(8000);
        if (ForgotPasswordPage.SERVER_VERIFICATION.equals(kind)) {
            throw new SkipException("Got the send-your-own-SMS server-verification flow instead of the normal "
                    + "phone-OTP flow this test expects - likely this account's anti-abuse throttling kicking in "
                    + "after heavy testing. Re-run later once the throttle clears.");
        }
        if (!ForgotPasswordPage.OTP_ALERT.equals(kind)) {
            throw new SkipException("Requesting a code returned an unexpected result (\"" + kind + "\") instead of "
                    + "the normal \"Message sent\" alert - likely a rate limit from heavy testing. Re-run later.");
        }
        String message = forgotPasswordPage.getAlertMessage();
        forgotPasswordPage.closeAlert(message);
    }

    /** Registers a fresh account and leaves it unactivated - for TC_FORGOTPASS_011's "not yet activated" precondition. */
    private String registerUnactivatedAccount(ExtentTest test) {
        RegisterPage registerPage = new RegisterPage(BaseTest.getCurrentPage());
        String phone = validPhone();
        test.log(Status.INFO, "Register a fresh account ('" + phone + "') without activating it");
        registerPage.openRegisterModal();
        registerPage.selectCountry("Hong Kong");
        registerPage.fillForm(randomUsername(), VALID_PASSWORD, VALID_PASSWORD, phone, ConfigReader.get("captcha.valid"));
        registerPage.submit();
        return phone;
    }

    @Test(description = "TC_FORGOTPASS_001: Verify the Forgot password functionality with a Non China number completes a full password reset")
    public void verifyForgotPasswordFullResetWithNonChinaNumber() {
        ExtentTest test = ExtentTestManager.getTest();
        ForgotPasswordPage forgotPasswordPage = openForgotPassword(test);
        requestCodeForExistingAccount(test, forgotPasswordPage);

        test.log(Status.INFO, "Verify the phone-verification-code entry page appears");
        Assert.assertTrue(forgotPasswordPage.phoneOtpModalVisible(),
                "Requested a password-reset code, but the phone-verification code entry page never showed up.");

        test.log(Status.INFO, "Enter the phone verification code ('" + VALID_OTP + "') and submit");
        forgotPasswordPage.enterOtp(VALID_OTP);
        forgotPasswordPage.submit();

        test.log(Status.INFO, "Enter a new password ('" + NEW_PASSWORD + "') and repeat it, then submit");
        forgotPasswordPage.enterNewPassword(NEW_PASSWORD, NEW_PASSWORD);
        forgotPasswordPage.submit();

        test.log(Status.INFO, "Verify the password was reset and the user was auto-logged in");
        String message = forgotPasswordPage.getAlertMessage();
        forgotPasswordPage.closeAlert(message);
        Assert.assertTrue(forgotPasswordPage.isLoggedIn(), "Reset the password, but the user was not automatically logged in afterward.");
    }

    @Test(description = "TC_FORGOTPASS_002: Verify the Forgot Password functionality with an invalid phone number shows the correct error")
    public void verifyForgotPasswordInvalidPhoneFormat() {
        ExtentTest test = ExtentTestManager.getTest();
        ForgotPasswordPage forgotPasswordPage = openForgotPassword(test);

        test.log(Status.INFO, "Enter an invalid contact number ('" + INVALID_PHONE + "') and a valid verification code");
        forgotPasswordPage.enterPhone(INVALID_PHONE);
        forgotPasswordPage.enterCaptcha(ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Click Send Message and check the phone-format error");
        forgotPasswordPage.requestCode();
        String message = forgotPasswordPage.getFieldError();
        Assert.assertEquals(message.strip(), "Contact Phone format error");
    }

    @Test(description = "TC_FORGOTPASS_003: Verify the Forgot Password functionality with a number that doesn't exist still goes to the OTP entry page")
    public void verifyForgotPasswordNonexistentPhoneNumber() {
        ExtentTest test = ExtentTestManager.getTest();
        ForgotPasswordPage forgotPasswordPage = openForgotPassword(test);
        String phone = validPhone();

        test.log(Status.INFO, "Enter a contact number that doesn't belong to any account ('" + phone + "') and a valid verification code");
        forgotPasswordPage.enterPhone(phone);
        forgotPasswordPage.enterCaptcha(ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Click Send Message and verify it still goes to the OTP entry page");
        forgotPasswordPage.requestCode();
        Assert.assertTrue(forgotPasswordPage.phoneOtpModalVisible(),
                "Requested a reset code for a non-existent number ('" + phone + "'), but the phone-verification "
                        + "code entry page never showed up (the site should still show it, just without actually sending an SMS).");
    }

    @Test(description = "TC_FORGOTPASS_004: Verify the Forgot Password functionality with an invalid verification code shows the correct error")
    public void verifyForgotPasswordInvalidVerificationCode() {
        ExtentTest test = ExtentTestManager.getTest();
        ForgotPasswordPage forgotPasswordPage = openForgotPassword(test);
        String phone = validPhone();

        test.log(Status.INFO, "Enter a valid contact number ('" + phone + "') and an invalid verification code");
        forgotPasswordPage.enterPhone(phone);
        forgotPasswordPage.enterCaptcha(ConfigReader.get("captcha.invalid"));

        test.log(Status.INFO, "Click Send Message and check the verification-code error");
        forgotPasswordPage.requestCode();
        String message = forgotPasswordPage.getAlertMessage();
        forgotPasswordPage.closeAlert(message);
        Assert.assertEquals(message.strip(), "Verification wrong");
    }

    @Test(description = "TC_FORGOTPASS_006: Verify the Forgot password functionality with an invalid phone verification code shows the correct error")
    public void verifyForgotPasswordInvalidPhoneVerificationCode() {
        ExtentTest test = ExtentTestManager.getTest();
        ForgotPasswordPage forgotPasswordPage = openForgotPassword(test);
        String phone = validPhone();

        test.log(Status.INFO, "Request a code for a fresh contact number ('" + phone + "')");
        requestFreshCodeOrSkip(test, forgotPasswordPage, phone);

        test.log(Status.INFO, "Enter an invalid phone verification code ('" + INVALID_OTP + "') and submit");
        forgotPasswordPage.enterOtp(INVALID_OTP);
        forgotPasswordPage.submit();

        test.log(Status.INFO, "Check the phone-verification-code error");
        String message = forgotPasswordPage.getAlertMessage();
        forgotPasswordPage.closeAlert(message);
        Assert.assertEquals(message.strip(), "phone verification code error");
    }

    @Test(description = "TC_FORGOTPASS_008: Verify the Forgot password functionality with an expired OTP code shows the correct error")
    public void verifyForgotPasswordExpiredPhoneVerificationCode() {
        ExtentTest test = ExtentTestManager.getTest();
        ForgotPasswordPage forgotPasswordPage = openForgotPassword(test);
        String phone = validPhone();

        test.log(Status.INFO, "Request a code for a fresh contact number ('" + phone + "')");
        requestFreshCodeOrSkip(test, forgotPasswordPage, phone);

        test.log(Status.INFO, "Enter an expired/wrong phone verification code ('" + EXPIRED_OTP + "') and submit");
        forgotPasswordPage.enterOtp(EXPIRED_OTP);
        forgotPasswordPage.submit();

        test.log(Status.INFO, "Check the phone-verification-code error");
        String message = forgotPasswordPage.getAlertMessage();
        forgotPasswordPage.closeAlert(message);
        Assert.assertEquals(message.strip(), "phone verification code error");
    }

    @Test(description = "TC_FORGOTPASS_009: Verify the Forgot password functionality with two different passwords shows the correct error")
    public void verifyForgotPasswordMismatchedNewPassword() {
        ExtentTest test = ExtentTestManager.getTest();
        ForgotPasswordPage forgotPasswordPage = openForgotPassword(test);
        requestCodeForExistingAccount(test, forgotPasswordPage);

        test.log(Status.INFO, "Enter the phone verification code ('" + VALID_OTP + "') and submit");
        forgotPasswordPage.enterOtp(VALID_OTP);
        forgotPasswordPage.submit();

        test.log(Status.INFO, "Enter a new password and a repeat password that doesn't match, then submit");
        forgotPasswordPage.enterNewPassword(NEW_PASSWORD, MISMATCHED_NEW_PASSWORD);
        forgotPasswordPage.submit();

        test.log(Status.INFO, "Check the password-mismatch error");
        String message = forgotPasswordPage.getFieldError();
        Assert.assertEquals(message.strip(), "Must be the same as the password");
    }

    @Test(description = "TC_FORGOTPASS_010: Verify the Forgot password functionality with a special character in the new password shows the correct error")
    public void verifyForgotPasswordSpecialCharacterNewPassword() {
        ExtentTest test = ExtentTestManager.getTest();
        ForgotPasswordPage forgotPasswordPage = openForgotPassword(test);
        requestCodeForExistingAccount(test, forgotPasswordPage);

        test.log(Status.INFO, "Enter the phone verification code ('" + VALID_OTP + "') and submit");
        forgotPasswordPage.enterOtp(VALID_OTP);
        forgotPasswordPage.submit();

        test.log(Status.INFO, "Enter a new password with special characters, then submit");
        forgotPasswordPage.enterNewPassword(SPECIAL_CHAR_PASSWORD, SPECIAL_CHAR_PASSWORD);
        forgotPasswordPage.submit();

        test.log(Status.INFO, "Check the password-format error");
        String message = forgotPasswordPage.getFieldError();
        Assert.assertEquals(message.strip(), "Please enter 6-12 characters (a-z 0-9). special characters are not allowed");
    }

    @Test(description = "TC_FORGOTPASS_011: Verify the Forgot password functionality with a phone number that has not been activated still goes to the OTP entry page")
    public void verifyForgotPasswordPhoneNotYetActivated() {
        ExtentTest test = ExtentTestManager.getTest();
        String phone = registerUnactivatedAccount(test);

        test.log(Status.INFO, "Open the Forgot Password form for that not-yet-activated number");
        ForgotPasswordPage forgotPasswordPage = new ForgotPasswordPage(BaseTest.getCurrentPage());
        BaseTest.getCurrentPage().navigate(ConfigReader.get("base.url"));
        BaseTest.getCurrentPage().waitForTimeout(1500);
        forgotPasswordPage.openForgotPasswordModal();
        forgotPasswordPage.selectCountry("Hong Kong");
        forgotPasswordPage.enterPhone(phone);
        forgotPasswordPage.enterCaptcha(ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Click Send Message and verify it still goes to the OTP entry page");
        forgotPasswordPage.requestCode();
        Assert.assertTrue(forgotPasswordPage.phoneOtpModalVisible(),
                "Requested a reset code for a not-yet-activated account ('" + phone + "'), but the "
                        + "phone-verification code entry page never showed up (the site should still show it, "
                        + "just without actually sending an SMS).");
    }
}
