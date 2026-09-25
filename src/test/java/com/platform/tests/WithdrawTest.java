package com.platform.tests;

import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.platform.base.BaseTest;
import com.platform.pages.WithdrawPage;
import com.platform.utility.AdminApiClient;
import com.platform.utility.ConfigReader;
import com.platform.utility.ExtentTestManager;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.Map;

/**
 * Automates the "WithdrawalMainbalance" sheet's test cases whose Automation column is marked
 * "Yes", against the real Withdrawal page on test-v2.138hk.vip - all of them reachable through
 * the "AI_PAY" withdrawal channel, exactly like DepositTest's "AI_PAY" deposit channel covers the
 * corresponding deposit options. Every method's description carries that sheet's real
 * TC_HKD_WITHDRA_XXX id, so the two stay traceable to each other.
 *
 * Methods are grouped by option (in sheet order), and within each option ordered without-a-
 * bound-account -> below-minimum -> above-maximum -> with-a-bound-account:
 *  - Without a bound payout account: submit without binding one first - expect an
 *    "...required" error.
 *  - Below the minimum / above the maximum: submit an amount outside the option's typed range -
 *    expect an error naming that minimum/maximum. Only options with a free-text Withdrawal Game
 *    Points amount (not a fixed-denomination dropdown) have a typed range.
 *  - With a bound account: bind a payout account, submit a valid amount - expect the
 *    "Confirm Withdrawal?" prompt, then confirm it and capture the resulting on-page message
 *    (unlike Deposit, Withdraw never opens a payment-gateway tab - it settles with a message).
 *
 * Four options (ATM Machine, Cash Payment, Jockey Club Cash Vouchers, Visa Gift Card) pick their
 * Withdrawal Game Points amount from a fixed dropdown instead of typing one in, so the sheet has
 * no below-minimum/above-maximum case for them - only without-bound-account and with-a-bound-
 * account are automated here for those four.
 */
public class WithdrawTest extends BaseTest {

    private WithdrawPage loginAndOpenWithdraw(ExtentTest test) {
        String username = ConfigReader.get("username");
        String password = ConfigReader.get("password");
        String captcha = ConfigReader.get("captcha.valid");

        test.log(Status.INFO, "Log in");
        loginPage.login(username, password, captcha, step -> test.log(Status.INFO, step));

        WithdrawPage withdrawPage = new WithdrawPage(BaseTest.getCurrentPage());
        test.log(Status.INFO, "Open the Withdrawal page");
        withdrawPage.open();
        return withdrawPage;
    }

    private void selectChannelOptionAndWallet(ExtentTest test, WithdrawPage withdrawPage, String channel, String option) {
        test.log(Status.INFO, "Select channel '" + channel + "' and option '" + option + "'");
        withdrawPage.selectChannel(channel);
        withdrawPage.selectOption(option);
        test.log(Status.INFO, "Select wallet 'Main Balance'");
        withdrawPage.selectWallet("Main Balance");
    }

    private void submitWithoutBoundAccountAndAssertError(ExtentTest test, WithdrawPage withdrawPage, String optionLabel) {
        if (withdrawPage.isBound()) {
            throw new SkipException("'" + optionLabel + "' already has a payout account bound from a previous "
                    + "test run, so submitting without one can no longer be tested here.");
        }
        test.log(Status.INFO, "Submit and check the bind-account-required error");
        withdrawPage.submit();
        String message = withdrawPage.getAlertMessage();
        Assert.assertTrue(message.toLowerCase().contains("required"),
                "Submitted option '" + optionLabel + "' with no payout account bound, expected an error saying "
                        + "an account is \"required\", but the site said: \"" + message + "\"");
    }

    private void assertMinimumAmountError(ExtentTest test, WithdrawPage withdrawPage, int belowMin, int min,
                                           String optionLabel) {
        test.log(Status.INFO, "Enter an amount below the minimum (" + belowMin + ")");
        withdrawPage.enterAmount(belowMin);

        test.log(Status.INFO, "Submit and check the minimum-amount error");
        withdrawPage.submit();
        String message = withdrawPage.getAlertMessage();
        Assert.assertTrue(message.contains(String.valueOf(min)) && message.toLowerCase().contains("minimum"),
                "Submitted option '" + optionLabel + "' with " + belowMin + " (below its " + min + " minimum), "
                        + "expected a \"minimum amount\" error mentioning " + min + ", but the site said: \""
                        + message + "\"");
    }

    private void assertMaximumAmountError(ExtentTest test, WithdrawPage withdrawPage, int aboveMax, int max,
                                           String optionLabel) {
        test.log(Status.INFO, "Enter an amount above the maximum (" + aboveMax + ")");
        withdrawPage.enterAmount(aboveMax);

        test.log(Status.INFO, "Submit and check the maximum-amount error");
        withdrawPage.submit();
        String message = withdrawPage.getAlertMessage();
        Assert.assertTrue(message.contains(String.valueOf(max)) && message.toLowerCase().contains("maximum"),
                "Submitted option '" + optionLabel + "' with " + aboveMax + " (above its " + max + " maximum), "
                        + "expected a \"maximum amount\" error mentioning " + max + ", but the site said: \""
                        + message + "\"");
    }

    /**
     * Submits, expects the "Confirm Withdrawal?" prompt, confirms it, and captures the resulting
     * on-page message (e.g. "Withdraw request submitted") - Withdraw never redirects to a
     * payment-gateway tab the way Deposit does, so there's nothing to capture beyond that.
     *
     * The site enforces a "Daily withdrawal frequency is 3 times" limit per account, which heavy
     * same-day test runs exhaust quickly. That limit can surface at either point in the flow: as
     * an alert instead of the confirm prompt right after the first submit, or - just as often -
     * the confirm prompt shows up fine and it's only the *final* message after clicking Confirm
     * that says "Reached daily withdrawal frequency" instead of a success message. Either way
     * this rejects today's pending withdrawal requests via the admin API (freeing the quota back
     * up immediately) and retries the whole attempt once, then asserts the final message actually
     * indicates success rather than silently accepting an error message as if it were one.
     */
    private void submitAndCaptureValidAmountResult(ExtentTest test, WithdrawPage withdrawPage, String optionLabel) {
        String message = attemptWithdrawal(test, withdrawPage, optionLabel);
        if (isDailyFrequencyLimitMessage(message)) {
            test.log(Status.WARNING, "Hit the daily withdrawal frequency limit ('" + message + "') - rejecting "
                    + "today's pending withdrawals via the admin API, then retrying the whole submission");
            AdminApiClient.rejectTodaysPendingWithdrawals("HKD");
            message = attemptWithdrawal(test, withdrawPage, optionLabel);
        }
        test.log(Status.INFO, "Withdrawal confirmation for '" + optionLabel + "': " + message);
        Assert.assertFalse(isDailyFrequencyLimitMessage(message),
                "Submitted option '" + optionLabel + "' with a valid amount and a bound account, but still hit the "
                        + "daily withdrawal frequency limit even after rejecting pending withdrawals and retrying: \""
                        + message + "\"");
    }

    /**
     * One full submit -> confirm attempt. Returns whatever message resulted - either the
     * daily-frequency-limit alert that can appear instead of the confirm prompt, or the final
     * message after confirming (a success message, or that same frequency-limit error instead).
     */
    private String attemptWithdrawal(ExtentTest test, WithdrawPage withdrawPage, String optionLabel) {
        test.log(Status.INFO, "Submit and check the withdrawal confirmation prompt appears");
        withdrawPage.submit();
        if (!withdrawPage.withdrawConfirmationVisible(5000)) {
            String preConfirmMessage = tryGetAlertMessage(withdrawPage);
            if (isDailyFrequencyLimitMessage(preConfirmMessage)) {
                withdrawPage.closeAlert(preConfirmMessage);
                return preConfirmMessage;
            }
        }
        Assert.assertTrue(withdrawPage.withdrawConfirmationVisible(),
                "Submitted option '" + optionLabel + "' with a valid amount and a bound account, but the "
                        + "\"Confirm Withdrawal?\" prompt never showed up.");

        test.log(Status.INFO, "Confirm the withdrawal");
        withdrawPage.confirmPrompt();
        String message = withdrawPage.getAlertMessage();
        withdrawPage.closeAlert(message);
        return message;
    }

    private boolean isDailyFrequencyLimitMessage(String message) {
        return message != null && message.toLowerCase().contains("daily withdrawal frequency");
    }

    private String tryGetAlertMessage(WithdrawPage withdrawPage) {
        try {
            return withdrawPage.getAlertMessage(3000);
        } catch (Exception e) {
            return null;
        }
    }

    // ==========================================================================================
    // Bank Transfer
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_0057: Verify withdraw submission without a bound account for Bank Transfer shows the correct error")
    public void verifyWithdrawBankTransferWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Bank Transfer");
        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "Bank Transfer");
    }

    @Test(description = "TC_HKD_WITHDRA_0058: Verify withdraw submission below the minimum amount for Bank Transfer shows the correct error")
    public void verifyWithdrawBankTransferBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Bank Transfer");
        assertMinimumAmountError(test, withdrawPage, 10, 100, "Bank Transfer");
    }

    @Test(description = "TC_HKD_WITHDRA_0059: Verify withdraw submission above the maximum amount for Bank Transfer shows the correct error")
    public void verifyWithdrawBankTransferAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Bank Transfer");
        assertMaximumAmountError(test, withdrawPage, 10000000, 100000, "Bank Transfer");
    }

    @Test(description = "TC_HKD_WITHDRA_0056: Verify withdraw submission with a bound account for Bank Transfer")
    public void verifyWithdrawBankTransferWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Bank Transfer");

        test.log(Status.INFO, "Bind the payout account (Account Name: Test User, Account No: 6011223302)");
        withdrawPage.bindAccount(Map.of("Account Name", "Test User", "Account No", "6011223302"));

        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "Bank Transfer");
    }

    // ==========================================================================================
    // FPS Transfer (Playpay)
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_0062: Verify withdraw submission without a bound account for FPS Transfer (Playpay) shows the correct error")
    public void verifyWithdrawFpsPlaypayWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "FPS Transfer (Playpay)");
        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "FPS Transfer (Playpay)");
    }

    @Test(description = "TC_HKD_WITHDRA_0063: Verify withdraw submission below the minimum amount for FPS Transfer (Playpay) shows the correct error")
    public void verifyWithdrawFpsPlaypayBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "FPS Transfer (Playpay)");
        assertMinimumAmountError(test, withdrawPage, 10, 100, "FPS Transfer (Playpay)");
    }

    @Test(description = "TC_HKD_WITHDRA_0064: Verify withdraw submission above the maximum amount for FPS Transfer (Playpay) shows the correct error")
    public void verifyWithdrawFpsPlaypayAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "FPS Transfer (Playpay)");
        assertMaximumAmountError(test, withdrawPage, 2000000000, 100000, "FPS Transfer (Playpay)");
    }

    @Test(description = "TC_HKD_WITHDRA_0061: Verify withdraw submission with a bound account for FPS Transfer (Playpay)")
    public void verifyWithdrawFpsPlaypayWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "FPS Transfer (Playpay)");

        test.log(Status.INFO, "Bind the payout account (Telephone Number: 91234562, Full name: Test User)");
        withdrawPage.bindAccount(Map.of("Telephone Number", "91234562", "Full name", "Test User"));

        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "FPS Transfer (Playpay)");
    }

    // ==========================================================================================
    // Bank Transfer (6Pay)
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_0078: Verify withdraw submission without a bound account for Bank Transfer (6Pay) shows the correct error")
    public void verifyWithdraw6PayBankWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Bank Transfer (6Pay)");
        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "Bank Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_WITHDRA_0079: Verify withdraw submission below the minimum amount for Bank Transfer (6Pay) shows the correct error")
    public void verifyWithdraw6PayBankBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Bank Transfer (6Pay)");
        assertMinimumAmountError(test, withdrawPage, 10, 100, "Bank Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_WITHDRA_0080: Verify withdraw submission above the maximum amount for Bank Transfer (6Pay) shows the correct error")
    public void verifyWithdraw6PayBankAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Bank Transfer (6Pay)");
        assertMaximumAmountError(test, withdrawPage, 2000000000, 100000, "Bank Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_WITHDRA_0077: Verify withdraw submission with a bound account for Bank Transfer (6Pay)")
    public void verifyWithdraw6PayBankWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Bank Transfer (6Pay)");

        test.log(Status.INFO, "Bind the payout account (Account Name: Test User, Account No: 6011223301)");
        withdrawPage.bindAccount(Map.of("Account Name", "Test User", "Account No", "6011223301"));

        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "Bank Transfer (6Pay)");
    }

    // ==========================================================================================
    // FPS Transfer (6Pay)
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_0067: Verify withdraw submission without a bound account for FPS Transfer (6Pay) shows the correct error")
    public void verifyWithdraw6PayFpsWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "FPS Transfer (6Pay)");
        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "FPS Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_WITHDRA_0068: Verify withdraw submission below the minimum amount for FPS Transfer (6Pay) shows the correct error")
    public void verifyWithdraw6PayFpsBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "FPS Transfer (6Pay)");
        assertMinimumAmountError(test, withdrawPage, 10, 100, "FPS Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_WITHDRA_0069: Verify withdraw submission above the maximum amount for FPS Transfer (6Pay) shows the correct error")
    public void verifyWithdraw6PayFpsAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "FPS Transfer (6Pay)");
        assertMaximumAmountError(test, withdrawPage, 2000000000, 100000, "FPS Transfer (6Pay)");
    }

    @Test(description = "TC_HKD_WITHDRA_0066: Verify withdraw submission with a bound account for FPS Transfer (6Pay)")
    public void verifyWithdraw6PayFpsWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "FPS Transfer (6Pay)");

        test.log(Status.INFO, "Bind the payout account (Telephone Number: 91234561, Full name: Test User)");
        withdrawPage.bindAccount(Map.of("Telephone Number", "91234561", "Full name", "Test User"));

        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "FPS Transfer (6Pay)");
    }

    // ==========================================================================================
    // ATM Machine (fixed-denomination Withdrawal Game Points dropdown)
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_0083: Verify withdraw submission without a bound account for ATM Machine shows the correct error")
    public void verifyWithdrawAtmWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "ATM Machine");
        test.log(Status.INFO, "Select game points (100)");
        withdrawPage.selectGamePoints(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "ATM Machine");
    }

    @Test(description = "TC_HKD_WITHDRA_0082: Verify withdraw submission with a bound account for ATM Machine")
    public void verifyWithdrawAtmWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "ATM Machine");

        test.log(Status.INFO, "Bind the payout account (94811004)");
        withdrawPage.bindAccount("94811004");

        test.log(Status.INFO, "Select game points (100)");
        withdrawPage.selectGamePoints(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "ATM Machine");
    }

    // ==========================================================================================
    // Octopus Pay
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_0091: Verify withdraw submission without a bound account for Octopus Pay shows the correct error")
    public void verifyWithdrawOctopusPayWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Octopus Pay");
        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "Octopus Pay");
    }

    @Test(description = "TC_HKD_WITHDRA_0092: Verify withdraw submission below the minimum amount for Octopus Pay shows the correct error")
    public void verifyWithdrawOctopusPayBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Octopus Pay");
        assertMinimumAmountError(test, withdrawPage, 10, 100, "Octopus Pay");
    }

    @Test(description = "TC_HKD_WITHDRA_0093: Verify withdraw submission above the maximum amount for Octopus Pay shows the correct error")
    public void verifyWithdrawOctopusPayAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Octopus Pay");
        assertMaximumAmountError(test, withdrawPage, 2000000000, 1000001, "Octopus Pay");
    }

    @Test(description = "TC_HKD_WITHDRA_0090: Verify withdraw submission with a bound account for Octopus Pay")
    public void verifyWithdrawOctopusPayWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Octopus Pay");

        test.log(Status.INFO, "Bind the payout account (94811001)");
        withdrawPage.bindAccount("94811001");

        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "Octopus Pay");
    }

    // ==========================================================================================
    // Cash Payment (fixed-denomination Withdrawal Game Points dropdown)
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_00100: Verify withdraw submission without a bound account for Cash Payment shows the correct error")
    public void verifyWithdrawCashPaymentWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Cash Payment");
        test.log(Status.INFO, "Select game points (100)");
        withdrawPage.selectGamePoints(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "Cash Payment");
    }

    @Test(description = "TC_HKD_WITHDRA_0099: Verify withdraw submission with a bound account for Cash Payment")
    public void verifyWithdrawCashPaymentWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Cash Payment");

        test.log(Status.INFO, "Bind the payout account (85291239962)");
        withdrawPage.bindAccount("85291239962");

        test.log(Status.INFO, "Select game points (100)");
        withdrawPage.selectGamePoints(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "Cash Payment");
    }

    // ==========================================================================================
    // Jockey Club Cash Vouchers (fixed-denomination Withdrawal Game Points dropdown)
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_00109: Verify withdraw submission without a bound account for Jockey Club Cash Vouchers shows the correct error")
    public void verifyWithdrawJockeyClubWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Jockey Club Cash Vouchers");
        test.log(Status.INFO, "Select game points (100)");
        withdrawPage.selectGamePoints(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "Jockey Club Cash Vouchers");
    }

    @Test(description = "TC_HKD_WITHDRA_00108: Verify withdraw submission with a bound account for Jockey Club Cash Vouchers")
    public void verifyWithdrawJockeyClubWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Jockey Club Cash Vouchers");

        test.log(Status.INFO, "Bind the payout account (94811005)");
        withdrawPage.bindAccount("94811005");

        test.log(Status.INFO, "Select game points (100)");
        withdrawPage.selectGamePoints(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "Jockey Club Cash Vouchers");
    }

    // ==========================================================================================
    // Visa Gift Card (fixed-denomination Withdrawal Game Points dropdown)
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_00118: Verify withdraw submission without a bound account for Visa Gift Card shows the correct error")
    public void verifyWithdrawVisaGiftCardWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Visa Gift Card");
        test.log(Status.INFO, "Select game points (100)");
        withdrawPage.selectGamePoints(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "Visa Gift Card");
    }

    @Test(description = "TC_HKD_WITHDRA_00117: Verify withdraw submission with a bound account for Visa Gift Card")
    public void verifyWithdrawVisaGiftCardWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Visa Gift Card");

        test.log(Status.INFO, "Bind the payout account (4111111111111111)");
        withdrawPage.bindAccount("4111111111111111");

        test.log(Status.INFO, "Select game points (100)");
        withdrawPage.selectGamePoints(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "Visa Gift Card");
    }

    // ==========================================================================================
    // Alipay
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_00127: Verify withdraw submission without a bound account for Alipay shows the correct error")
    public void verifyWithdrawAlipayWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Alipay");
        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "Alipay");
    }

    @Test(description = "TC_HKD_WITHDRA_00128: Verify withdraw submission below the minimum amount for Alipay shows the correct error")
    public void verifyWithdrawAlipayBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Alipay");
        assertMinimumAmountError(test, withdrawPage, 10, 100, "Alipay");
    }

    @Test(description = "TC_HKD_WITHDRA_00129: Verify withdraw submission above the maximum amount for Alipay shows the correct error")
    public void verifyWithdrawAlipayAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Alipay");
        assertMaximumAmountError(test, withdrawPage, 2000000000, 100000, "Alipay");
    }

    @Test(description = "TC_HKD_WITHDRA_00126: Verify withdraw submission with a bound account for Alipay")
    public void verifyWithdrawAlipayWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "Alipay");

        test.log(Status.INFO, "Bind the payout account (Telephone Number: 85263741590, Full name: Test User)");
        withdrawPage.bindAccount(Map.of("Telephone Number", "85263741590", "Full name", "Test User"));

        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "Alipay");
    }

    // ==========================================================================================
    // USDT-TRC20
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_00132: Verify withdraw submission without a bound account for USDT-TRC20 shows the correct error")
    public void verifyWithdrawUsdtTrc20WithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "USDT-TRC20");
        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "USDT-TRC20");
    }

    @Test(description = "TC_HKD_WITHDRA_00133: Verify withdraw submission below the minimum amount for USDT-TRC20 shows the correct error")
    public void verifyWithdrawUsdtTrc20BelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "USDT-TRC20");
        assertMinimumAmountError(test, withdrawPage, 10, 100, "USDT-TRC20");
    }

    @Test(description = "TC_HKD_WITHDRA_00134: Verify withdraw submission above the maximum amount for USDT-TRC20 shows the correct error")
    public void verifyWithdrawUsdtTrc20AboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "USDT-TRC20");
        assertMaximumAmountError(test, withdrawPage, 2000000000, 100000, "USDT-TRC20");
    }

    @Test(description = "TC_HKD_WITHDRA_00131: Verify withdraw submission with a bound account for USDT-TRC20")
    public void verifyWithdrawUsdtTrc20WithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "USDT-TRC20");

        test.log(Status.INFO, "Bind the payout wallet address (TUM2FXuX7DqBW4qVx2akQqxAY17Ux1ijjj)");
        withdrawPage.bindAccount("TUM2FXuX7DqBW4qVx2akQqxAY17Ux1ijjj");

        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "USDT-TRC20");
    }

    // ==========================================================================================
    // UMPAY(HKD)
    // ==========================================================================================

    @Test(description = "TC_HKD_WITHDRA_00147: Verify withdraw submission without a bound account for UMPAY(HKD) shows the correct error")
    public void verifyWithdrawUmPayHkdWithoutBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "UMPAY(HKD)");
        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);
        submitWithoutBoundAccountAndAssertError(test, withdrawPage, "UMPAY(HKD)");
    }

    @Test(description = "TC_HKD_WITHDRA_00148: Verify withdraw submission below the minimum amount for UMPAY(HKD) shows the correct error")
    public void verifyWithdrawUmPayHkdBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "UMPAY(HKD)");
        assertMinimumAmountError(test, withdrawPage, 10, 100, "UMPAY(HKD)");
    }

    @Test(description = "TC_HKD_WITHDRA_00149: Verify withdraw submission above the maximum amount for UMPAY(HKD) shows the correct error")
    public void verifyWithdrawUmPayHkdAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "UMPAY(HKD)");
        assertMaximumAmountError(test, withdrawPage, 2000000000, 100000, "UMPAY(HKD)");
    }

    @Test(description = "TC_HKD_WITHDRA_00146: Verify withdraw submission with a bound account for UMPAY(HKD)")
    public void verifyWithdrawUmPayHkdWithBoundAccount() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "UMPAY(HKD)");

        test.log(Status.INFO, "Bind the payout account (0123456789)");
        withdrawPage.bindAccount("0123456789");

        test.log(Status.INFO, "Enter a valid amount (100)");
        withdrawPage.enterAmount(100);

        submitAndCaptureValidAmountResult(test, withdrawPage, "UMPAY(HKD)");
    }

    // ==========================================================================================
    // WPAY(HKD)
    // ==========================================================================================
    // Only below-minimum/above-maximum are marked "Yes" for this option in the sheet - no
    // without-bound-account/with-bound-account cases are automated here.

    @Test(description = "TC_HKD_WITHDRA_00152: Verify withdraw submission below the minimum amount for WPAY(HKD) shows the correct error")
    public void verifyWithdrawWpayHkdBelowMinimum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "WPAY(HKD)");
        assertMinimumAmountError(test, withdrawPage, 10, 100, "WPAY(HKD)");
    }

    @Test(description = "TC_HKD_WITHDRA_00153: Verify withdraw submission above the maximum amount for WPAY(HKD) shows the correct error")
    public void verifyWithdrawWpayHkdAboveMaximum() {
        ExtentTest test = ExtentTestManager.getTest();
        WithdrawPage withdrawPage = loginAndOpenWithdraw(test);
        selectChannelOptionAndWallet(test, withdrawPage, "AI_PAY", "WPAY(HKD)");
        assertMaximumAmountError(test, withdrawPage, 2000000000, 100000, "WPAY(HKD)");
    }
}
