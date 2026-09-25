package com.platform.tests;

import com.aventstack.extentreports.ExtentTest;
import com.aventstack.extentreports.Status;
import com.platform.base.BaseTest;
import com.platform.pages.TransactionRecordPage;
import com.platform.utility.ConfigReader;
import com.platform.utility.ExtentTestManager;
import org.testng.Assert;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Automates the Records page's transaction-record test cases, ported from the Python
 * Platform_automation project (testcases/transaction_record.py + pages/transactionRecordPage.py).
 * The Python source itself is data-driven (pytest.mark.parametrize over record type and time
 * filter); this expands every combination into its own hardcoded method, matching this project's
 * established one-test-case-per-method style.
 *
 * The spreadsheet's "Transaction Record" tab reuses the same TC_TRANSAC_XXX numbering (restarting
 * from 001) separately for most of its many near-identical currency blocks, with no currency
 * prefix distinguishing them from each other - but the very first block, directly under that
 * tab's "HKD" section header, spells out every record type in full (empty data, all four preset
 * time filters, custom date, totals, Check button) with one continuously-numbered TC_TRANSAC_001
 * -062 run, and its exact shape - including Lucky Draw being the only type with no "empty data"
 * case - matches this class method-for-method. Every method's description below cites that
 * block's real id(s); the two-total-rows-in-one-method totals tests cite both of that record
 * type's "Total of current page" / "Total of all records" sheet rows.
 *
 * Every record type covered on the Records page. Transfer and Order Amendment (labelled
 * "Adjustment" on the site) don't show a "Details" column with a Check button, and only Deposit,
 * Withdrawal, Betting and Bonus Promotion ever show a totals footer. Lucky Draw's own QA test
 * cases don't include an "empty data" scenario, so it has no verify*EmptyData method here.
 */
public class TransactionRecordTest extends BaseTest {

    private TransactionRecordPage loginAndOpenRecords(ExtentTest test) {
        String username = ConfigReader.get("username");
        String password = ConfigReader.get("password");
        String captcha = ConfigReader.get("captcha.valid");

        test.log(Status.INFO, "Log in");
        loginPage.login(username, password, captcha, step -> test.log(Status.INFO, step));

        TransactionRecordPage recordsPage = new TransactionRecordPage(BaseTest.getCurrentPage());
        test.log(Status.INFO, "Open the Records page");
        recordsPage.open();
        return recordsPage;
    }

    /** With a custom date range guaranteed to have no activity (year 2000), expect the "No match result found." empty state. */
    private void assertEmptyData(ExtentTest test, String label) {
        TransactionRecordPage recordsPage = loginAndOpenRecords(test);
        test.log(Status.INFO, "Select record type '" + label + "' and a date range with no activity");
        recordsPage.selectRecordType(label);
        recordsPage.setCustomDate("2000-01-01 00:00:00", "2000-01-02 23:59:59");

        test.log(Status.INFO, "Submit and check the empty-state message");
        recordsPage.submit();
        Assert.assertTrue(recordsPage.hasNoMatch(),
                "Filtered '" + label + "' records to a date range with no activity (2000-01-01 - 2000-01-02), but "
                        + "the empty-state \"No match result found.\" message never showed up.");
    }

    /**
     * Select a preset Transaction Time filter (Today, Yesterday, This week, Last week) and check
     * that every row returned actually falls inside the date range the site itself computed for
     * that preset (read back from the auto-filled From/To fields). An empty result is also
     * accepted - the account may simply have no activity of that type in the window.
     */
    private void assertPresetTimeFilter(ExtentTest test, String label, int dateColumn, String timeFilter) {
        TransactionRecordPage recordsPage = loginAndOpenRecords(test);
        test.log(Status.INFO, "Select record type '" + label + "' and transaction time '" + timeFilter + "'");
        recordsPage.selectRecordType(label);
        recordsPage.selectTransactionTime(timeFilter);
        LocalDateTime[] range = recordsPage.getEffectiveRange();

        test.log(Status.INFO, "Submit and check every returned row falls within " + range[0] + " - " + range[1]);
        recordsPage.submit();
        assertRowsWithinRange(recordsPage.getRowDates(dateColumn), range[0], range[1], label, timeFilter);
    }

    /** Same check as the preset filter, but with a manually-entered custom date range (the last 30 days) instead. */
    private void assertCustomDateFilter(ExtentTest test, String label, int dateColumn) {
        TransactionRecordPage recordsPage = loginAndOpenRecords(test);
        LocalDateTime to = LocalDateTime.now().withHour(23).withMinute(59).withSecond(59).withNano(0);
        LocalDateTime from = to.minusDays(30).withHour(0).withMinute(0).withSecond(0);

        test.log(Status.INFO, "Select record type '" + label + "' and a custom date range (" + from + " - " + to + ")");
        recordsPage.selectRecordType(label);
        recordsPage.setCustomDate(from.format(TransactionRecordPage.DATE_FORMAT), to.format(TransactionRecordPage.DATE_FORMAT));

        test.log(Status.INFO, "Submit and check every returned row falls within the selected range");
        recordsPage.submit();
        assertRowsWithinRange(recordsPage.getRowDates(dateColumn), from, to, label, "custom date");
    }

    private void assertRowsWithinRange(List<LocalDateTime> rows, LocalDateTime from, LocalDateTime to, String label, String filterLabel) {
        List<LocalDateTime> outOfRange = rows.stream().filter(d -> d.isBefore(from) || d.isAfter(to)).toList();
        Assert.assertTrue(outOfRange.isEmpty(),
                "'" + label + "' records filtered by '" + filterLabel + "' (" + from + " - " + to + ") included "
                        + "rows outside that range: " + outOfRange);
    }

    /** Check the "Total of current page" / "Total of records" footer rows show a well-formed amount rather than being blank or malformed. */
    private void assertTotalsDisplayed(ExtentTest test, String label) {
        TransactionRecordPage recordsPage = loginAndOpenRecords(test);
        test.log(Status.INFO, "Select record type '" + label + "' and transaction time 'This week'");
        recordsPage.selectRecordType(label);
        recordsPage.selectTransactionTime("This week");

        test.log(Status.INFO, "Submit and check the totals footer");
        recordsPage.submit();
        String[] totals = recordsPage.getTotals();
        Assert.assertTrue(TransactionRecordPage.isAmountText(totals[0]),
                "'" + label + "' records: expected \"Total of current page\" to show an amount like \"212.00\", "
                        + "but got \"" + totals[0] + "\"");
        Assert.assertTrue(TransactionRecordPage.isAmountText(totals[1]),
                "'" + label + "' records: expected \"Total of records\" to show an amount like \"212.00\", but got \""
                        + totals[1] + "\"");
    }

    /** Click the "Check" button on the first record and expect a "Details of record" popup, closeable via its "Close" button. */
    private void assertCheckButtonShowsDetailsPopup(ExtentTest test, String label) {
        TransactionRecordPage recordsPage = loginAndOpenRecords(test);
        test.log(Status.INFO, "Select record type '" + label + "' and transaction time 'This week'");
        recordsPage.selectRecordType(label);
        recordsPage.selectTransactionTime("This week");
        recordsPage.submit();

        if (recordsPage.rowCount() == 0) {
            throw new SkipException("No '" + label + "' records in the last week to click Check on.");
        }

        test.log(Status.INFO, "Click Check on the first record and check the details popup");
        recordsPage.clickCheckOnRow(0);
        Assert.assertTrue(recordsPage.detailsPopupVisible(8000),
                "Clicked Check on a '" + label + "' record, but the \"Details of record\" popup never showed up.");
        recordsPage.closeDetailsPopup();
    }

    // ==========================================================================================
    // Empty data (Deposit, Withdrawal, Transfer, Betting, Bonus Reward, Order Amendment)
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_008: Verify Deposit records with a date range with no activity shows the empty-state message")
    public void verifyDepositRecordsEmptyData() {
        assertEmptyData(ExtentTestManager.getTest(), "Deposit");
    }

    @Test(description = "TC_TRANSAC_017: Verify Withdrawal records with a date range with no activity shows the empty-state message")
    public void verifyWithdrawalRecordsEmptyData() {
        assertEmptyData(ExtentTestManager.getTest(), "Withdrawal");
    }

    @Test(description = "TC_TRANSAC_026: Verify Transfer records with a date range with no activity shows the empty-state message")
    public void verifyTransferRecordsEmptyData() {
        assertEmptyData(ExtentTestManager.getTest(), "Transfer Record");
    }

    @Test(description = "TC_TRANSAC_034: Verify Betting records with a date range with no activity shows the empty-state message")
    public void verifyBettingRecordsEmptyData() {
        assertEmptyData(ExtentTestManager.getTest(), "Betting");
    }

    @Test(description = "TC_TRANSAC_044: Verify Bonus Reward records with a date range with no activity shows the empty-state message")
    public void verifyBonusRewardRecordsEmptyData() {
        assertEmptyData(ExtentTestManager.getTest(), "Bonus Promotion");
    }

    @Test(description = "TC_TRANSAC_052: Verify Order Amendment records with a date range with no activity shows the empty-state message")
    public void verifyOrderAmendmentRecordsEmptyData() {
        assertEmptyData(ExtentTestManager.getTest(), "Adjustment");
    }

    // ==========================================================================================
    // Preset time filter - Deposit
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_009: Verify Deposit records filtered by 'Today' fall within the site's computed range")
    public void verifyDepositRecordsPresetFilterToday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Deposit", 0, "Today");
    }

    @Test(description = "TC_TRANSAC_010: Verify Deposit records filtered by 'Yesterday' fall within the site's computed range")
    public void verifyDepositRecordsPresetFilterYesterday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Deposit", 0, "Yesterday");
    }

    @Test(description = "TC_TRANSAC_011: Verify Deposit records filtered by 'This week' fall within the site's computed range")
    public void verifyDepositRecordsPresetFilterThisWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Deposit", 0, "This week");
    }

    @Test(description = "TC_TRANSAC_012: Verify Deposit records filtered by 'Last week' fall within the site's computed range")
    public void verifyDepositRecordsPresetFilterLastWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Deposit", 0, "Last week");
    }

    // ==========================================================================================
    // Preset time filter - Withdrawal
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_018: Verify Withdrawal records filtered by 'Today' fall within the site's computed range")
    public void verifyWithdrawalRecordsPresetFilterToday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Withdrawal", 0, "Today");
    }

    @Test(description = "TC_TRANSAC_019: Verify Withdrawal records filtered by 'Yesterday' fall within the site's computed range")
    public void verifyWithdrawalRecordsPresetFilterYesterday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Withdrawal", 0, "Yesterday");
    }

    @Test(description = "TC_TRANSAC_020: Verify Withdrawal records filtered by 'This week' fall within the site's computed range")
    public void verifyWithdrawalRecordsPresetFilterThisWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Withdrawal", 0, "This week");
    }

    @Test(description = "TC_TRANSAC_021: Verify Withdrawal records filtered by 'Last week' fall within the site's computed range")
    public void verifyWithdrawalRecordsPresetFilterLastWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Withdrawal", 0, "Last week");
    }

    // ==========================================================================================
    // Preset time filter - Transfer
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_027: Verify Transfer records filtered by 'Today' fall within the site's computed range")
    public void verifyTransferRecordsPresetFilterToday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Transfer Record", 0, "Today");
    }

    @Test(description = "TC_TRANSAC_028: Verify Transfer records filtered by 'Yesterday' fall within the site's computed range")
    public void verifyTransferRecordsPresetFilterYesterday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Transfer Record", 0, "Yesterday");
    }

    @Test(description = "TC_TRANSAC_029: Verify Transfer records filtered by 'This week' fall within the site's computed range")
    public void verifyTransferRecordsPresetFilterThisWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Transfer Record", 0, "This week");
    }

    @Test(description = "TC_TRANSAC_030: Verify Transfer records filtered by 'Last week' fall within the site's computed range")
    public void verifyTransferRecordsPresetFilterLastWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Transfer Record", 0, "Last week");
    }

    // ==========================================================================================
    // Preset time filter - Betting
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_037: Verify Betting records filtered by 'Today' fall within the site's computed range")
    public void verifyBettingRecordsPresetFilterToday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Betting", 2, "Today");
    }

    @Test(description = "TC_TRANSAC_038: Verify Betting records filtered by 'Yesterday' fall within the site's computed range")
    public void verifyBettingRecordsPresetFilterYesterday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Betting", 2, "Yesterday");
    }

    @Test(description = "TC_TRANSAC_039: Verify Betting records filtered by 'This week' fall within the site's computed range")
    public void verifyBettingRecordsPresetFilterThisWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Betting", 2, "This week");
    }

    @Test(description = "TC_TRANSAC_040: Verify Betting records filtered by 'Last week' fall within the site's computed range")
    public void verifyBettingRecordsPresetFilterLastWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Betting", 2, "Last week");
    }

    // ==========================================================================================
    // Preset time filter - Bonus Reward
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_045: Verify Bonus Reward records filtered by 'Today' fall within the site's computed range")
    public void verifyBonusRewardRecordsPresetFilterToday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Bonus Promotion", 0, "Today");
    }

    @Test(description = "TC_TRANSAC_046: Verify Bonus Reward records filtered by 'Yesterday' fall within the site's computed range")
    public void verifyBonusRewardRecordsPresetFilterYesterday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Bonus Promotion", 0, "Yesterday");
    }

    @Test(description = "TC_TRANSAC_047: Verify Bonus Reward records filtered by 'This week' fall within the site's computed range")
    public void verifyBonusRewardRecordsPresetFilterThisWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Bonus Promotion", 0, "This week");
    }

    @Test(description = "TC_TRANSAC_048: Verify Bonus Reward records filtered by 'Last week' fall within the site's computed range")
    public void verifyBonusRewardRecordsPresetFilterLastWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Bonus Promotion", 0, "Last week");
    }

    // ==========================================================================================
    // Preset time filter - Order Amendment
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_053: Verify Order Amendment records filtered by 'Today' fall within the site's computed range")
    public void verifyOrderAmendmentRecordsPresetFilterToday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Adjustment", 0, "Today");
    }

    @Test(description = "TC_TRANSAC_054: Verify Order Amendment records filtered by 'Yesterday' fall within the site's computed range")
    public void verifyOrderAmendmentRecordsPresetFilterYesterday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Adjustment", 0, "Yesterday");
    }

    @Test(description = "TC_TRANSAC_055: Verify Order Amendment records filtered by 'This week' fall within the site's computed range")
    public void verifyOrderAmendmentRecordsPresetFilterThisWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Adjustment", 0, "This week");
    }

    @Test(description = "TC_TRANSAC_056: Verify Order Amendment records filtered by 'Last week' fall within the site's computed range")
    public void verifyOrderAmendmentRecordsPresetFilterLastWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Adjustment", 0, "Last week");
    }

    // ==========================================================================================
    // Preset time filter - Lucky Draw
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_058: Verify Lucky Draw records filtered by 'Today' fall within the site's computed range")
    public void verifyLuckyDrawRecordsPresetFilterToday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Lucky spin", 0, "Today");
    }

    @Test(description = "TC_TRANSAC_059: Verify Lucky Draw records filtered by 'Yesterday' fall within the site's computed range")
    public void verifyLuckyDrawRecordsPresetFilterYesterday() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Lucky spin", 0, "Yesterday");
    }

    @Test(description = "TC_TRANSAC_060: Verify Lucky Draw records filtered by 'This week' fall within the site's computed range")
    public void verifyLuckyDrawRecordsPresetFilterThisWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Lucky spin", 0, "This week");
    }

    @Test(description = "TC_TRANSAC_061: Verify Lucky Draw records filtered by 'Last week' fall within the site's computed range")
    public void verifyLuckyDrawRecordsPresetFilterLastWeek() {
        assertPresetTimeFilter(ExtentTestManager.getTest(), "Lucky spin", 0, "Last week");
    }

    // ==========================================================================================
    // Custom date filter (last 30 days)
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_013: Verify Deposit records filtered by a custom date range fall within that range")
    public void verifyDepositRecordsCustomDateFilter() {
        assertCustomDateFilter(ExtentTestManager.getTest(), "Deposit", 0);
    }

    @Test(description = "TC_TRANSAC_022: Verify Withdrawal records filtered by a custom date range fall within that range")
    public void verifyWithdrawalRecordsCustomDateFilter() {
        assertCustomDateFilter(ExtentTestManager.getTest(), "Withdrawal", 0);
    }

    @Test(description = "TC_TRANSAC_031: Verify Transfer records filtered by a custom date range fall within that range")
    public void verifyTransferRecordsCustomDateFilter() {
        assertCustomDateFilter(ExtentTestManager.getTest(), "Transfer Record", 0);
    }

    @Test(description = "TC_TRANSAC_041: Verify Betting records filtered by a custom date range fall within that range")
    public void verifyBettingRecordsCustomDateFilter() {
        assertCustomDateFilter(ExtentTestManager.getTest(), "Betting", 2);
    }

    @Test(description = "TC_TRANSAC_049: Verify Bonus Reward records filtered by a custom date range fall within that range")
    public void verifyBonusRewardRecordsCustomDateFilter() {
        assertCustomDateFilter(ExtentTestManager.getTest(), "Bonus Promotion", 0);
    }

    @Test(description = "TC_TRANSAC_057: Verify Order Amendment records filtered by a custom date range fall within that range")
    public void verifyOrderAmendmentRecordsCustomDateFilter() {
        assertCustomDateFilter(ExtentTestManager.getTest(), "Adjustment", 0);
    }

    @Test(description = "TC_TRANSAC_062: Verify Lucky Draw records filtered by a custom date range fall within that range")
    public void verifyLuckyDrawRecordsCustomDateFilter() {
        assertCustomDateFilter(ExtentTestManager.getTest(), "Lucky spin", 0);
    }

    // ==========================================================================================
    // Totals footer (Deposit, Withdrawal, Betting, Bonus Reward)
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_014, TC_TRANSAC_015: Verify Deposit records show well-formed totals in the footer")
    public void verifyDepositRecordsTotalsDisplayed() {
        assertTotalsDisplayed(ExtentTestManager.getTest(), "Deposit");
    }

    @Test(description = "TC_TRANSAC_023, TC_TRANSAC_024: Verify Withdrawal records show well-formed totals in the footer")
    public void verifyWithdrawalRecordsTotalsDisplayed() {
        assertTotalsDisplayed(ExtentTestManager.getTest(), "Withdrawal");
    }

    @Test(description = "TC_TRANSAC_042, TC_TRANSAC_043: Verify Betting records show well-formed totals in the footer")
    public void verifyBettingRecordsTotalsDisplayed() {
        assertTotalsDisplayed(ExtentTestManager.getTest(), "Betting");
    }

    @Test(description = "TC_TRANSAC_050, TC_TRANSAC_051: Verify Bonus Reward records show well-formed totals in the footer")
    public void verifyBonusRewardRecordsTotalsDisplayed() {
        assertTotalsDisplayed(ExtentTestManager.getTest(), "Bonus Promotion");
    }

    // ==========================================================================================
    // Check button -> Details popup (Deposit, Withdrawal)
    // ==========================================================================================

    @Test(description = "TC_TRANSAC_016: Verify clicking Check on a Deposit record shows the details popup")
    public void verifyDepositRecordsCheckButtonShowsDetailsPopup() {
        assertCheckButtonShowsDetailsPopup(ExtentTestManager.getTest(), "Deposit");
    }

    @Test(description = "TC_TRANSAC_025: Verify clicking Check on a Withdrawal record shows the details popup")
    public void verifyWithdrawalRecordsCheckButtonShowsDetailsPopup() {
        assertCheckButtonShowsDetailsPopup(ExtentTestManager.getTest(), "Withdrawal");
    }
}
