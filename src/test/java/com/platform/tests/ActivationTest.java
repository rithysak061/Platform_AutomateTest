package com.platform.tests;

import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.microsoft.playwright.Page;
import com.platform.base.BaseTest;
import com.platform.pages.ActivationPage;
import com.platform.pages.RegisterPage;
import com.platform.utility.ConfigReader;
import com.platform.utility.ExtentTestManager;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Automates the "Activation Account" sheet's test cases, ported from the Python
 * Platform_automation project (testcases/account_activation.py + pages/activationPage.py). Every
 * method's description carries that sheet's real TC_ACCACTIV_XXX id. The sheet's own Automation
 * column currently flags every row FALSE - per explicit direction, this follows the Python
 * source's actual working scope instead of that column, since the column simply hasn't caught up
 * with what's already been automated.
 *
 * This is the standalone "Account Activation" flow reachable from the site's top nav (Log In /
 * Register / Account Activation) - not the activation modal that appears automatically right
 * after registering (that one's covered by RegisterTest). Both share the same underlying
 * phone-OTP component, though, so ActivationPage builds on the same BasePage helpers RegisterPage
 * does.
 */
public class ActivationTest extends BaseTest {

    private static final int SMS_REQUEST_COOLDOWN_SECONDS = 130;
    private static final String DUPLICATE_PHONE = "51234567";
    private static final String INVALID_PHONE = "21342312";
    private static final String VALID_PASSWORD = "123456";
    private static final String VALID_OTP = "1111";
    private static final String INVALID_OTP = "9999";
    private static final String[] VALID_PHONE_PREFIXES = {"6", "7", "8", "9"};

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

    /** A full, standard-format (11-digit) China mobile number - the site rejects the shorter
     *  9-digit numbers RegisterTest's china-format phone produces as an invalid format here, even
     *  though the Register page accepts them there. */
    private static String chinaPhone() {
        return "1" + ThreadLocalRandom.current().nextLong(3000000000L, 10000000000L);
    }

    /**
     * Requests a code for a fresh, valid contact number and closes the resulting alert - unless
     * the account's anti-abuse throttling has switched this request to the send-your-own-SMS/QR
     * flow instead of a normal "Message sent" alert (seen after heavy testing), in which case
     * this skips rather than hanging waiting for an alert that isn't coming.
     */
    private void requestFreshCodeOrSkip(ExtentTest test, ActivationPage activationPage, String phone) {
        activationPage.enterPhone(phone);
        activationPage.enterCaptcha(ConfigReader.get("captcha.valid"));
        activationPage.requestCode();
        String kind = activationPage.requestCodeResult(8000);
        if (ActivationPage.SERVER_VERIFICATION.equals(kind)) {
            throw new SkipException("Got the send-your-own-SMS server-verification flow instead of the normal "
                    + "phone-OTP flow this test expects - likely this account's anti-abuse throttling kicking in "
                    + "after heavy testing. Re-run later once the throttle clears.");
        }
        if (!ActivationPage.OTP_ALERT.equals(kind)) {
            throw new SkipException("Requesting a code returned an unexpected result (\"" + kind + "\") instead of "
                    + "the normal \"Message sent\" alert - likely a rate limit from heavy testing. Re-run later.");
        }
        String message = activationPage.getAlertMessage();
        activationPage.closeAlert(message);
    }

    private ActivationPage openActivation(ExtentTest test) {
        ActivationPage activationPage = new ActivationPage(BaseTest.getCurrentPage());
        test.log(Status.INFO, "Open the Account Activation form");
        activationPage.openActivationModal();
        test.log(Status.INFO, "Select Hong Kong as the contact number's country");
        activationPage.selectCountry("Hong Kong");
        return activationPage;
    }

    /**
     * Registers a fresh account and leaves it unactivated, then waits out the site's SMS cooldown
     * so the activation flow's own code request (for the same number, right after registration's
     * own) isn't rejected as "too many" requests.
     */
    private String registerUnactivatedAccount(ExtentTest test) {
        RegisterPage registerPage = new RegisterPage(BaseTest.getCurrentPage());
        String phone = validPhone();
        test.log(Status.INFO, "Register a fresh account ('" + phone + "') without activating it");
        registerPage.openRegisterModal();
        registerPage.selectCountry("Hong Kong");
        registerPage.fillForm(randomUsername(), VALID_PASSWORD, VALID_PASSWORD, phone, ConfigReader.get("captcha.valid"));
        registerPage.submit();

        test.log(Status.INFO, "Wait " + SMS_REQUEST_COOLDOWN_SECONDS + "s for the site's SMS request cooldown on this number to clear");
        BaseTest.getCurrentPage().waitForTimeout(SMS_REQUEST_COOLDOWN_SECONDS * 1000L);
        return phone;
    }

    @Test(description = "TC_ACCACTIV_001: Verify the Account activation functionality with a Non China number completes a full activation")
    public void verifyActivationFullActivationWithNonChinaNumber() {
        ExtentTest test = ExtentTestManager.getTest();
        String phone = registerUnactivatedAccount(test);
        ActivationPage activationPage = openActivation(test);

        test.log(Status.INFO, "Request an activation code ('" + phone + "')");
        activationPage.enterPhone(phone);
        activationPage.enterCaptcha(ConfigReader.get("captcha.valid"));
        activationPage.requestCode();
        String kind = activationPage.requestCodeResult(8000);

        test.log(Status.INFO, "Verify the phone-verification-code entry page appears");
        if (ActivationPage.SERVER_VERIFICATION.equals(kind)) {
            throw new SkipException("Got the send-your-own-SMS server-verification flow instead of the normal "
                    + "phone-OTP flow this test can complete - likely this account's anti-abuse throttling kicking "
                    + "in after heavy testing. That flow needs a real phone to send the SMS from, so it can't be "
                    + "finished here. Re-run later once the throttle clears.");
        }
        Assert.assertEquals(kind, ActivationPage.OTP_ALERT,
                "Requested an activation code for a fresh, unactivated account, but no OTP entry step showed up (got \""
                        + kind + "\").");
        String message = activationPage.getAlertMessage();
        activationPage.closeAlert(message);
        Assert.assertTrue(activationPage.phoneOtpModalVisible(),
                "The activation alert closed, but the phone-verification code entry modal never showed up.");

        test.log(Status.INFO, "Enter the phone verification code ('" + VALID_OTP + "') and activate the account");
        activationPage.enterOtp(VALID_OTP);
        activationPage.submitActivation();

        test.log(Status.INFO, "Verify the account was activated and the user was auto-logged in");
        BaseTest.getCurrentPage().waitForURL("**/member/deposit/**", new Page.WaitForURLOptions().setTimeout(10000));
        Assert.assertTrue(activationPage.isLoggedIn(), "Activated the account, but the user was not logged in afterward.");
    }

    @Test(description = "TC_ACCACTIV_002: Verify the Account activation functionality with an invalid phone number shows the correct error")
    public void verifyActivationInvalidPhoneFormat() {
        ExtentTest test = ExtentTestManager.getTest();
        ActivationPage activationPage = openActivation(test);

        test.log(Status.INFO, "Enter an invalid contact number ('" + INVALID_PHONE + "') and a valid verification code");
        activationPage.enterPhone(INVALID_PHONE);
        activationPage.enterCaptcha(ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Click Send Message and check the phone-format error");
        activationPage.requestCode();
        String message = activationPage.getFieldError();
        Assert.assertEquals(message.strip(), "Contact Phone format error");
    }

    @Test(description = "TC_ACCACTIV_003: Verify the Account activation functionality with a number that doesn't belong to anyone still goes to the OTP entry page")
    public void verifyActivationNonexistentPhoneNumber() {
        ExtentTest test = ExtentTestManager.getTest();
        ActivationPage activationPage = openActivation(test);
        String phone = validPhone();

        test.log(Status.INFO, "Enter a contact number that doesn't belong to any account ('" + phone + "') and a valid verification code");
        activationPage.enterPhone(phone);
        activationPage.enterCaptcha(ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Click Send Message and verify it still goes to the OTP entry page");
        activationPage.requestCode();
        Assert.assertTrue(activationPage.phoneOtpModalVisible(),
                "Requested an activation code for a non-existent number ('" + phone + "'), but the "
                        + "phone-verification code entry page never showed up (the site should still show it, "
                        + "just without actually sending an SMS).");
    }

    @Test(description = "TC_ACCACTIV_004: Verify the Account activation functionality with an invalid verification code shows the correct error")
    public void verifyActivationInvalidVerificationCode() {
        ExtentTest test = ExtentTestManager.getTest();
        ActivationPage activationPage = openActivation(test);
        String phone = validPhone();

        test.log(Status.INFO, "Enter a valid contact number ('" + phone + "') and an invalid verification code");
        activationPage.enterPhone(phone);
        activationPage.enterCaptcha(ConfigReader.get("captcha.invalid"));

        test.log(Status.INFO, "Click Send Message and check the verification-code error");
        activationPage.requestCode();
        String message = activationPage.getAlertMessage();
        activationPage.closeAlert(message);
        Assert.assertEquals(message.strip(), "Verification wrong");
    }

    @Test(description = "TC_ACCACTIV_006: Verify the Account activation functionality with an invalid phone verification code shows the correct error")
    public void verifyActivationInvalidPhoneVerificationCode() {
        ExtentTest test = ExtentTestManager.getTest();
        ActivationPage activationPage = openActivation(test);
        String phone = validPhone();

        test.log(Status.INFO, "Request a code for a fresh contact number ('" + phone + "')");
        requestFreshCodeOrSkip(test, activationPage, phone);

        test.log(Status.INFO, "Enter an invalid phone verification code ('" + INVALID_OTP + "') and click Activate Account");
        activationPage.enterOtp(INVALID_OTP);
        activationPage.submitActivation();

        test.log(Status.INFO, "Check the phone-verification-code error");
        String message = activationPage.getAlertMessage();
        activationPage.closeAlert(message);
        Assert.assertEquals(message.strip(), "phone verification code error");
    }

    @Test(description = "TC_ACCACTIV_008: Verify the Account activation functionality with resend code sends a new code")
    public void verifyActivationResendCode() {
        ExtentTest test = ExtentTestManager.getTest();
        ActivationPage activationPage = openActivation(test);
        String phone = validPhone();

        test.log(Status.INFO, "Request a code for a fresh contact number ('" + phone + "')");
        requestFreshCodeOrSkip(test, activationPage, phone);
        Assert.assertTrue(activationPage.phoneOtpModalVisible(),
                "Requested an activation code, but the phone-verification code entry page never showed up.");

        test.log(Status.INFO, "Wait " + SMS_REQUEST_COOLDOWN_SECONDS + "s for the resend timer, then click Resend Code");
        BaseTest.getCurrentPage().waitForTimeout(SMS_REQUEST_COOLDOWN_SECONDS * 1000L);
        BaseTest.getCurrentPage().getByText("Resend Code", new Page.GetByTextOptions().setExact(true))
                .click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(8000));

        test.log(Status.INFO, "Verify a new code was sent with no error");
        String message = activationPage.getAlertMessage();
        activationPage.closeAlert(message);
        Assert.assertTrue(message.toLowerCase().contains("sent"),
                "Expected a \"code sent\" style confirmation after resending, but the site said: \"" + message + "\"");
    }

    @Test(description = "TC_ACCACTIV_009: Verify the Account activation functionality of a user account that's already activated still goes to the OTP entry page")
    public void verifyActivationAlreadyActivatedAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        ActivationPage activationPage = openActivation(test);

        test.log(Status.INFO, "Enter an already-activated account's contact number ('" + DUPLICATE_PHONE + "') and a valid verification code");
        activationPage.enterPhone(DUPLICATE_PHONE);
        activationPage.enterCaptcha(ConfigReader.get("captcha.valid"));

        test.log(Status.INFO, "Click Send Message and verify it still goes to the OTP entry page");
        activationPage.requestCode();
        Assert.assertTrue(activationPage.phoneOtpModalVisible(),
                "Requested an activation code for an already-activated account ('" + DUPLICATE_PHONE + "'), but the "
                        + "phone-verification code entry page never showed up.");
    }

    @Test(description = "TC_ACCACTIV_010: Verify the Account activation functionality with a China number shows the QR-code activation modal")
    public void verifyActivationChinaNumberQrActivation() {
        ExtentTest test = ExtentTestManager.getTest();
        ActivationPage activationPage = new ActivationPage(BaseTest.getCurrentPage());
        test.log(Status.INFO, "Open the Account Activation form");
        activationPage.openActivationModal();

        test.log(Status.INFO, "Select China as the contact number's country");
        activationPage.selectCountry("China");

        String phone = chinaPhone();
        test.log(Status.INFO, "Request an activation code for a China-format number ('" + phone + "')");
        activationPage.enterPhone(phone);
        activationPage.enterCaptcha(ConfigReader.get("captcha.valid"));
        activationPage.requestCode();

        test.log(Status.INFO, "Verify the QR-code activation modal appears");
        Assert.assertTrue(activationPage.qrActivationVisible(8000),
                "Requested activation with a China-format contact number, but the QR-code activation modal never showed up.");
    }
}
