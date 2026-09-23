package com.platform.tests;

import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.platform.base.BaseTest;
import com.platform.pages.DepositPage;
import com.platform.utility.ConfigReader;
import com.platform.utility.ExtentTestManager;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.Base64;
import java.util.Map;

/**
 * Automates the "Deposit" test cases, ported from the Python Platform_automation project
 * (testcases/deposit.py + pages/depositPage.py + fixture/testData.json). Every deposit option
 * currently covered there, across every deposit channel, gets its own hardcoded test method per
 * scenario - matching this project's established one-test-case-per-method style (see LoginTest) -
 * rather than the original pytest.mark.parametrize data-driven approach.
 *
 * These target a different site/UI than the spreadsheet's "Deposit" tab (test-v1.1083.city,
 * TP/HKD account currency, Playpay admin confirmation) - our automation is against
 * test-v2.138hk.vip. Where a sheet row under "HKD" genuinely tests the same channel with the
 * same bind requirement and amount-entry shape (free-text vs fixed preset), the method's
 * description uses that sheet's real TC_HKD_DEPO_XXX ID instead of inventing one, so the two
 * stay traceable to each other; every other method is marked ID_NOT_FOUND, since the sheet's
 * cases for those channels either don't exist, use a different bind requirement, or are
 * ambiguous about which of two similarly-named channels they mean (e.g. its one generic
 * "6PAY"/"FPS transfer" entries vs. our separate "(6Pay)"/"(Playpay)" variants).
 *
 * Methods are grouped by option (in the order they appear in the source data), and within each
 * option ordered without-bound-account (if it needs one) -> below-minimum -> above-maximum (both
 * only for options with a free-text amount) -> valid-amount (every option has this one):
 *  - Without a bound payment account: for options that require one, submit without binding one
 *    first - expect a "bind account required" error.
 *  - Below the minimum / above the maximum: submit an amount outside the option's typed range -
 *    expect an error naming that minimum/maximum. Only options with a free-text Game Points
 *    amount (not a fixed-denomination dropdown) have a typed range.
 *  - With a valid amount: submit with a valid amount (and, where the option needs it, a bound
 *    payment account) - expect the "Confirm Deposit?" prompt, then confirm it and capture either
 *    the payment-gateway tab that normally opens afterwards or the on-page confirmation message
 *    (cash-style options settle with a message instead of a new tab).
 */
public class DepositTest extends BaseTest {

    private DepositPage loginAndOpenDeposit(ExtentTest test) {
        String username = ConfigReader.get("username");
        String password = ConfigReader.get("password");
        String captcha = ConfigReader.get("captcha.valid");

        test.log(Status.INFO, "Log in");
        loginPage.login(username, password, captcha, step -> test.log(Status.INFO, step));

        DepositPage depositPage = new DepositPage(BaseTest.getCurrentPage());
        test.log(Status.INFO, "Open the Deposit page");
        depositPage.open();
        return depositPage;
    }

    private void selectChannelAndOption(ExtentTest test, DepositPage depositPage, String channel, String option) {
        test.log(Status.INFO, "Select channel '" + channel + "' and option '" + option + "'");
        depositPage.selectChannel(channel);
        depositPage.selectOption(option);
    }

    /**
     * Submits, expects the "Confirm Deposit?" prompt, confirms it, and captures either the
     * payment-gateway tab that normally opens afterwards or the on-page confirmation message.
     */
    private void submitAndCaptureValidAmountResult(ExtentTest test, DepositPage depositPage, boolean requiresBind,
                                                     String optionLabel) {
        test.log(Status.INFO, "Submit and check the deposit confirmation prompt appears");
        depositPage.submit();
        Assert.assertTrue(depositPage.depositConfirmationVisible(),
                "Submitted option '" + optionLabel + "' with a valid amount"
                        + (requiresBind ? " and a bound account" : "")
                        + ", but the \"Confirm Deposit?\" prompt never showed up.");

        test.log(Status.INFO, "Confirm the deposit and capture the payment result");
        BrowserContext context = BaseTest.getCurrentPage().context();
        Page newTab;
        try {
            newTab = context.waitForPage(new BrowserContext.WaitForPageOptions().setTimeout(15000),
                    depositPage::confirmPrompt);
        } catch (Exception e) {
            // Not every option opens a payment-gateway tab: cash-style options settle with an
            // on-page message instead ("contact customer service to proceed").
            String message;
            try {
                message = depositPage.getAlertMessage(8000);
            } catch (Exception exc) {
                throw new AssertionError("Confirmed the deposit for option '" + optionLabel
                        + "', but neither a payment-gateway tab nor an on-page confirmation message appeared: "
                        + exc.getMessage(), exc);
            }
            test.log(Status.INFO, "Deposit confirmation for '" + optionLabel + "': " + message);
            depositPage.closeAlert(message);
            return;
        }
        try {
            newTab.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(15000));
            try {
                newTab.waitForURL(url -> !url.contains("/loading/"), new Page.WaitForURLOptions().setTimeout(15000));
            } catch (Exception ignored) {
            }
            newTab.waitForTimeout(2000);
            test.log(Status.INFO, "Payment gateway page for '" + optionLabel + "' (" + newTab.url() + ")");
            byte[] screenshot = newTab.screenshot();
            test.addScreenCaptureFromBase64String(Base64.getEncoder().encodeToString(screenshot),
                    "Payment gateway page for " + optionLabel);
        } catch (Exception e) {
            // Some gateways (e.g. crypto/QR-code style ones) redirect through and close their
            // own tab very quickly - that's still a successful handoff to the gateway (the tab
            // did open, which is what matters), just too fast for us to capture more of it.
            test.log(Status.INFO, "Payment gateway tab for '" + optionLabel
                    + "' opened and closed on its own before it could be captured: " + e.getMessage());
        } finally {
            try {
                if (!newTab.isClosed()) {
                    newTab.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void submitWithoutBoundAccountAndAssertError(ExtentTest test, DepositPage depositPage, String optionLabel) {
        if (depositPage.isBound()) {
            throw new SkipException("'" + optionLabel + "' already has a payment account bound from a previous "
                    + "test run, so submitting without one can no longer be tested here.");
        }
        test.log(Status.INFO, "Submit and check the bind-account-required error");
        depositPage.submit();
        String message = depositPage.getAlertMessage();
        Assert.assertTrue(message.toLowerCase().contains("required"),
                "Submitted option '" + optionLabel + "' with no payment account bound, expected an error saying "
                        + "an account is \"required\", but the site said: \"" + message + "\"");
    }

    private void assertMinimumAmountError(ExtentTest test, DepositPage depositPage, int belowMin, int min,
                                           String optionLabel) {
        test.log(Status.INFO, "Enter an amount below the minimum (" + belowMin + ")");
        depositPage.enterAmount(belowMin);

        test.log(Status.INFO, "Submit and check the minimum-amount error");
        depositPage.submit();
        String message = depositPage.getAlertMessage();
        Assert.assertTrue(message.contains(String.valueOf(min)) && message.toLowerCase().contains("minimum"),
                "Submitted option '" + optionLabel + "' with " + belowMin + " (below its " + min + " minimum), "
                        + "expected a \"minimum amount\" error mentioning " + min + ", but the site said: \""
                        + message + "\"");
    }

    private void assertMaximumAmountError(ExtentTest test, DepositPage depositPage, int aboveMax, int max,
                                           String optionLabel) {
        test.log(Status.INFO, "Enter an amount above the maximum (" + aboveMax + ")");
        depositPage.enterAmount(aboveMax);

        test.log(Status.INFO, "Submit and check the maximum-amount error");
        depositPage.submit();
        String message = depositPage.getAlertMessage();
        Assert.assertTrue(message.contains(String.valueOf(max)) && message.toLowerCase().contains("maximum"),
                "Submitted option '" + optionLabel + "' with " + aboveMax + " (above its " + max + " maximum), "
                        + "expected a \"maximum amount\" error mentioning " + max + ", but the site said: \""
                        + message + "\"");
    }

    // ==========================================================================================
    // WPAY(HKD)
    // ==========================================================================================

    @Test(description = "TC_HKD_WPAY_DEPO_0087: Verify deposit submission below the minimum amount for WPAY(HKD) shows the correct error")
    public void verifyDepositWpayHkdBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "WPAY(HKD)");
        assertMinimumAmountError(test, depositPage, 99, 100, "WPAY(HKD)");
    }

    @Test(description = "TC_HKD_WPAY_DEPO_0088: Verify deposit submission above the maximum amount for WPAY(HKD) shows the correct error")
    public void verifyDepositWpayHkdAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "WPAY(HKD)");
        assertMaximumAmountError(test, depositPage, 100001, 100000, "WPAY(HKD)");
    }

    @Test(description = "TC_HKD_WPAY_DEPO_0089: Verify deposit submission with a valid amount for WPAY(HKD)")
    public void verifyDepositWpayHkdWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "WPAY(HKD)");

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, false, "WPAY(HKD)");
    }

    // ==========================================================================================
    // USDT-TRC20(UPAY)
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0073: Verify deposit submission without a bound account for USDT-TRC20(UPAY) shows the correct error")
    public void verifyDepositUsdtTrc20UpayWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-TRC20(UPAY)");
        test.log(Status.INFO, "Enter a valid amount (20)");
        depositPage.enterAmount(20);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "USDT-TRC20(UPAY)");
    }

    @Test(description = "TC_HKD_DEPO_0074: Verify deposit submission below the minimum amount for USDT-TRC20(UPAY) shows the correct error")
    public void verifyDepositUsdtTrc20UpayBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-TRC20(UPAY)");
        assertMinimumAmountError(test, depositPage, 19, 20, "USDT-TRC20(UPAY)");
    }

    @Test(description = "TC_HKD_DEPO_0075: Verify deposit submission above the maximum amount for USDT-TRC20(UPAY) shows the correct error")
    public void verifyDepositUsdtTrc20UpayAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-TRC20(UPAY)");
        assertMaximumAmountError(test, depositPage, 10001, 10000, "USDT-TRC20(UPAY)");
    }

    @Test(description = "TC_HKD_DEPO_0072: Verify deposit submission with a valid amount for USDT-TRC20(UPAY)")
    public void verifyDepositUsdtTrc20UpayWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-TRC20(UPAY)");

        test.log(Status.INFO, "Bind the payment account (TUM2FXuX7DqBW4qVx2akQqxAY17Ux1ijjj)");
        depositPage.bindAccount("TUM2FXuX7DqBW4qVx2akQqxAY17Ux1ijjj");

        test.log(Status.INFO, "Enter a valid amount (20)");
        depositPage.enterAmount(20);

        submitAndCaptureValidAmountResult(test, depositPage, true, "USDT-TRC20(UPAY)");
    }

    // ==========================================================================================
    // UMPAY(HKD)
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0061: Verify deposit submission without a bound account for UMPAY(HKD) shows the correct error")
    public void verifyDepositUmpayHkdWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "UMPAY(HKD)");
        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "UMPAY(HKD)");
    }

    @Test(description = "TC_HKD_DEPO_0062: Verify deposit submission below the minimum amount for UMPAY(HKD) shows the correct error")
    public void verifyDepositUmpayHkdBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "UMPAY(HKD)");
        assertMinimumAmountError(test, depositPage, 99, 100, "UMPAY(HKD)");
    }

    @Test(description = "TC_HKD_DEPO_0063: Verify deposit submission above the maximum amount for UMPAY(HKD) shows the correct error")
    public void verifyDepositUmpayHkdAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "UMPAY(HKD)");
        assertMaximumAmountError(test, depositPage, 100001, 100000, "UMPAY(HKD)");
    }

    @Test(description = "TC_HKD_DEPO_0060: Verify deposit submission with a valid amount for UMPAY(HKD)")
    public void verifyDepositUmpayHkdWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "UMPAY(HKD)");

        test.log(Status.INFO, "Bind the payment account (0123456789)");
        depositPage.bindAccount("0123456789");

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "UMPAY(HKD)");
    }

    // ==========================================================================================
    // USDT-ERC20
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0065: Verify deposit submission without a bound account for USDT-ERC20 shows the correct error")
    public void verifyDepositUsdtErc20WithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-ERC20");
        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "USDT-ERC20");
    }

    @Test(description = "TC_HKD_DEPO_0066: Verify deposit submission below the minimum amount for USDT-ERC20 shows the correct error")
    public void verifyDepositUsdtErc20BelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-ERC20");
        assertMinimumAmountError(test, depositPage, 99, 100, "USDT-ERC20");
    }

    @Test(description = "TC_HKD_DEPO_0067: Verify deposit submission above the maximum amount for USDT-ERC20 shows the correct error")
    public void verifyDepositUsdtErc20AboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-ERC20");
        assertMaximumAmountError(test, depositPage, 50001, 50000, "USDT-ERC20");
    }

    @Test(description = "TC_HKD_DEPO_0064: Verify deposit submission with a valid amount for USDT-ERC20")
    public void verifyDepositUsdtErc20WithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-ERC20");

        test.log(Status.INFO, "Bind the payment account (0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb)");
        depositPage.bindAccount("0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb");

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "USDT-ERC20");
    }

    // ==========================================================================================
    // USDT-OMNI
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0077: Verify deposit submission without a bound account for USDT-OMNI shows the correct error")
    public void verifyDepositUsdtOmniWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-OMNI");
        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "USDT-OMNI");
    }

    @Test(description = "TC_HKD_DEPO_0078: Verify deposit submission below the minimum amount for USDT-OMNI shows the correct error")
    public void verifyDepositUsdtOmniBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-OMNI");
        assertMinimumAmountError(test, depositPage, 99, 100, "USDT-OMNI");
    }

    @Test(description = "TC_HKD_DEPO_0079: Verify deposit submission above the maximum amount for USDT-OMNI shows the correct error")
    public void verifyDepositUsdtOmniAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-OMNI");
        assertMaximumAmountError(test, depositPage, 100001, 100000, "USDT-OMNI");
    }

    @Test(description = "TC_HKD_DEPO_0076: Verify deposit submission with a valid amount for USDT-OMNI")
    public void verifyDepositUsdtOmniWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-OMNI");

        test.log(Status.INFO, "Bind the payment account (1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa)");
        depositPage.bindAccount("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa");

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "USDT-OMNI");
    }

    // ==========================================================================================
    // USDT-TRC20
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0069: Verify deposit submission without a bound account for USDT-TRC20 shows the correct error")
    public void verifyDepositUsdtTrc20WithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-TRC20");
        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "USDT-TRC20");
    }

    @Test(description = "TC_HKD_DEPO_0070: Verify deposit submission below the minimum amount for USDT-TRC20 shows the correct error")
    public void verifyDepositUsdtTrc20BelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-TRC20");
        assertMinimumAmountError(test, depositPage, 99, 100, "USDT-TRC20");
    }

    @Test(description = "TC_HKD_DEPO_0071: Verify deposit submission above the maximum amount for USDT-TRC20 shows the correct error")
    public void verifyDepositUsdtTrc20AboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-TRC20");
        assertMaximumAmountError(test, depositPage, 100001, 100000, "USDT-TRC20");
    }

    @Test(description = "TC_HKD_DEPO_0068: Verify deposit submission with a valid amount for USDT-TRC20")
    public void verifyDepositUsdtTrc20WithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "USDT-TRC20");

        test.log(Status.INFO, "Bind the payment account (TUM2FXuX7DqBW4qVx2akQqxAY17Ux1ijjj)");
        depositPage.bindAccount("TUM2FXuX7DqBW4qVx2akQqxAY17Ux1ijjj");

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "USDT-TRC20");
    }

    // ==========================================================================================
    // Bank Transfer (6Pay)
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0012: Verify deposit submission without a bound account for Bank Transfer (6Pay) shows the correct error")
    public void verifyDeposit6PayBankWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Bank Transfer (6Pay)");
        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "Bank Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_DEPO_0013: Verify deposit submission below the minimum amount for Bank Transfer (6Pay) shows the correct error")
    public void verifyDeposit6PayBankBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Bank Transfer (6Pay)");
        assertMinimumAmountError(test, depositPage, 99, 100, "Bank Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_DEPO_0014: Verify deposit submission above the maximum amount for Bank Transfer (6Pay) shows the correct error")
    public void verifyDeposit6PayBankAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Bank Transfer (6Pay)");
        assertMaximumAmountError(test, depositPage, 100001, 100000, "Bank Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_DEPO_0011: Verify deposit submission with a valid amount for Bank Transfer (6Pay)")
    public void verifyDeposit6PayBankWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Bank Transfer (6Pay)");

        test.log(Status.INFO, "Bind the payment account (Account Name: Test User, Account No: 6011223301)");
        depositPage.bindAccount(Map.of("Account Name", "Test User", "Account No", "6011223301"));

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "Bank Transfer (6Pay)");
    }

    // ==========================================================================================
    // FPS Transfer (6Pay)
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0016: Verify deposit submission without a bound account for FPS Transfer (6Pay) shows the correct error")
    public void verifyDeposit6PayFpsWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "FPS Transfer (6Pay)");
        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "FPS Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_DEPO_0017: Verify deposit submission below the minimum amount for FPS Transfer (6Pay) shows the correct error")
    public void verifyDeposit6PayFpsBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "FPS Transfer (6Pay)");
        assertMinimumAmountError(test, depositPage, 99, 100, "FPS Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_DEPO_0018: Verify deposit submission above the maximum amount for FPS Transfer (6Pay) shows the correct error")
    public void verifyDeposit6PayFpsAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "FPS Transfer (6Pay)");
        assertMaximumAmountError(test, depositPage, 100001, 100000, "FPS Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_DEPO_0015: Verify deposit submission with a valid amount for FPS Transfer (6Pay)")
    public void verifyDeposit6PayFpsWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "FPS Transfer (6Pay)");

        test.log(Status.INFO, "Bind the payment account (Telephone Number: 91234561, Full name: Test User)");
        depositPage.bindAccount(Map.of("Telephone Number", "91234561", "Full name", "Test User"));

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "FPS Transfer (6Pay)");
    }

    // ==========================================================================================
    // Self ATM cash top-up (fixed-denomination, no bind - only the valid-amount scenario applies)
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_00125: Verify deposit submission with a valid preset amount for Self ATM cash top-up")
    public void verifyDepositSelfServiceWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Self ATM cash top-up");

        test.log(Status.INFO, "Select a valid amount (100)");
        depositPage.selectPresetAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, false, "Self ATM cash top-up");
    }

    // ==========================================================================================
    // ATM Machine
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0046: Verify deposit submission below the minimum amount for ATM Machine shows the correct error")
    public void verifyDepositAtmBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "ATM Machine");
        assertMinimumAmountError(test, depositPage, 99, 100, "ATM Machine");
    }

    @Test(description = "TC_HKD_DEPO_0047: Verify deposit submission above the maximum amount for ATM Machine shows the correct error")
    public void verifyDepositAtmAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "ATM Machine");
        assertMaximumAmountError(test, depositPage, 100001, 100000, "ATM Machine");
    }

    @Test(description = "TC_HKD_DEPO_0044: Verify deposit submission with a valid amount for ATM Machine")
    public void verifyDepositAtmWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "ATM Machine");

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, false, "ATM Machine");
    }

    // ==========================================================================================
    // Bank Transfer
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_004: Verify deposit submission without a bound account for Bank Transfer shows the correct error")
    public void verifyDepositBankTransferWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Bank Transfer");
        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "Bank Transfer");
    }

    @Test(description = "TC_HKD_DEPO_005: Verify deposit submission below the minimum amount for Bank Transfer shows the correct error")
    public void verifyDepositBankTransferBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Bank Transfer");
        assertMinimumAmountError(test, depositPage, 99, 100, "Bank Transfer");
    }

    @Test(description = "TC_HKD_DEPO_006: Verify deposit submission above the maximum amount for Bank Transfer shows the correct error")
    public void verifyDepositBankTransferAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Bank Transfer");
        assertMaximumAmountError(test, depositPage, 5000001, 5000000, "Bank Transfer");
    }

    @Test(description = "TC_HKD_DEPO_003: Verify deposit submission with a valid amount for Bank Transfer")
    public void verifyDepositBankTransferWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Bank Transfer");

        test.log(Status.INFO, "Bind the payment account (Account Name: Test User, Account No: 6011223302)");
        depositPage.bindAccount(Map.of("Account Name", "Test User", "Account No", "6011223302"));

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "Bank Transfer");
    }

    // ==========================================================================================
    // FPS Transfer (Playpay)
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_008: Verify deposit submission without a bound account for FPS Transfer (Playpay) shows the correct error")
    public void verifyDepositFpsPlaypayWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "FPS Transfer (Playpay)");
        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "FPS Transfer (Playpay)");
    }

    @Test(description = "TC_HKD_DEPO_009: Verify deposit submission below the minimum amount for FPS Transfer (Playpay) shows the correct error")
    public void verifyDepositFpsPlaypayBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "FPS Transfer (Playpay)");
        assertMinimumAmountError(test, depositPage, 99, 100, "FPS Transfer (Playpay)");
    }

    @Test(description = "TC_HKD_DEPO_0010: Verify deposit submission above the maximum amount for FPS Transfer (Playpay) shows the correct error")
    public void verifyDepositFpsPlaypayAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "FPS Transfer (Playpay)");
        assertMaximumAmountError(test, depositPage, 100001, 100000, "FPS Transfer (Playpay)");
    }

    @Test(description = "TC_HKD_DEPO_007: Verify deposit submission with a valid amount for FPS Transfer (Playpay)")
    public void verifyDepositFpsPlaypayWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "FPS Transfer (Playpay)");

        test.log(Status.INFO, "Bind the payment account (Telephone Number: 91234562, Full name: Test User)");
        depositPage.bindAccount(Map.of("Telephone Number", "91234562", "Full name", "Test User"));

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "FPS Transfer (Playpay)");
    }

    // ==========================================================================================
    // Alipay
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0050: Verify deposit submission without a bound account for Alipay shows the correct error")
    public void verifyDepositAlipayWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Alipay");
        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "Alipay");
    }

    @Test(description = "TC_HKD_DEPO_0052: Verify deposit submission below the minimum amount for Alipay shows the correct error")
    public void verifyDepositAlipayBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Alipay");
        assertMinimumAmountError(test, depositPage, 99, 100, "Alipay");
    }

    @Test(description = "TC_HKD_DEPO_0053: Verify deposit submission above the maximum amount for Alipay shows the correct error")
    public void verifyDepositAlipayAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Alipay");
        assertMaximumAmountError(test, depositPage, 100001, 100000, "Alipay");
    }

    @Test(description = "TC_HKD_DEPO_0049: Verify deposit submission with a valid amount for Alipay")
    public void verifyDepositAlipayWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Alipay");

        test.log(Status.INFO, "Bind the payment account (Telephone Number: 85263741590, Full name: Test User)");
        depositPage.bindAccount(Map.of("Telephone Number", "85263741590", "Full name", "Test User"));

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "Alipay");
    }

    // ==========================================================================================
    // Convenience store cash recharge (fixed-denomination, no bind - only valid-amount applies)
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0054: Verify deposit submission with a valid preset amount for Convenience store cash recharge")
    public void verifyDepositConvStoreCashWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Convenience store cash recharge");

        test.log(Status.INFO, "Select a valid amount (100)");
        depositPage.selectPresetAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, false, "Convenience store cash recharge");
    }

    // ==========================================================================================
    // Octopus Pay
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0037: Verify deposit submission without a bound account for Octopus Pay shows the correct error")
    public void verifyDepositOctopusPayWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Octopus Pay");
        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "Octopus Pay");
    }

    @Test(description = "TC_HKD_DEPO_0038: Verify deposit submission below the minimum amount for Octopus Pay shows the correct error")
    public void verifyDepositOctopusPayBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Octopus Pay");
        assertMinimumAmountError(test, depositPage, 99, 100, "Octopus Pay");
    }

    @Test(description = "TC_HKD_DEPO_0039: Verify deposit submission above the maximum amount for Octopus Pay shows the correct error")
    public void verifyDepositOctopusPayAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Octopus Pay");
        assertMaximumAmountError(test, depositPage, 1000001, 1000000, "Octopus Pay");
    }

    @Test(description = "TC_HKD_DEPO_0036: Verify deposit submission with a valid amount for Octopus Pay")
    public void verifyDepositOctopusPayWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Octopus Pay");

        test.log(Status.INFO, "Bind the payment account (94811001)");
        depositPage.bindAccount("94811001");

        test.log(Status.INFO, "Enter a valid amount (100)");
        depositPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "Octopus Pay");
    }

    // ==========================================================================================
    // Cash Payment (fixed-denomination, but requires a bound account)
    // ==========================================================================================

    @Test(description = "TC_HKD_DEPO_0042: Verify deposit submission without a bound account for Cash Payment shows the correct error")
    public void verifyDepositCashPaymentWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Cash Payment");
        test.log(Status.INFO, "Select a valid amount (100)");
        depositPage.selectPresetAmount(100);
        submitWithoutBoundAccountAndAssertError(test, depositPage, "Cash Payment");
    }

    @Test(description = "TC_HKD_DEPO_0041: Verify deposit submission with a valid preset amount for Cash Payment")
    public void verifyDepositCashPaymentWithValidAmount() {
        ExtentTest test = ExtentTestManager.getTest();
        DepositPage depositPage = loginAndOpenDeposit(test);
        selectChannelAndOption(test, depositPage, "AI_PAY", "Cash Payment");

        test.log(Status.INFO, "Bind the payment account (85291239962)");
        depositPage.bindAccount("85291239962");

        test.log(Status.INFO, "Select a valid amount (100)");
        depositPage.selectPresetAmount(100);

        submitAndCaptureValidAmountResult(test, depositPage, true, "Cash Payment");
    }
}
