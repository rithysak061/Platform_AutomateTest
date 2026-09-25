package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Locators ported from the Python Platform_automation project's pages/transactionRecordPage.py,
 * verified against the real Records page on https://test-v2.138hk.vip.
 */
public class TransactionRecordPage extends BasePage {

    public static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern AMOUNT_PATTERN = Pattern.compile("-?[\\d,]+\\.\\d{2}");

    private static final String SEARCH_WRAPPER = ".Record_search-wrapper__59j_z";
    private static final String TABLE = ".Record_table__jEwfw";
    private static final String CHECK_BUTTON = ".Record_check-btn__RjdtE";

    public TransactionRecordPage(Page page) {
        super(page);
    }

    public void open() {
        for (int attempt = 0; attempt < 3; attempt++) {
            page.getByText("Deposit", new Page.GetByTextOptions().setExact(true)).first().click();
            try {
                page.waitForURL("**/member/deposit/**",
                        new Page.WaitForURLOptions().setTimeout(6000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                break;
            } catch (RuntimeException e) {
                if (attempt == 2) {
                    throw e;
                }
            }
        }
        page.waitForTimeout(500);
        dismissPromoPopup();
        page.getByText("Records", new Page.GetByTextOptions().setExact(true)).first().click();
        page.waitForURL("**/member/record/**",
                new Page.WaitForURLOptions().setTimeout(8000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
        recordTypeSelect().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(10000));
        page.waitForTimeout(500);
    }

    private Locator searchWrapper() {
        return page.locator(SEARCH_WRAPPER);
    }

    private Locator recordTypeSelect() {
        return searchWrapper().locator("select").nth(0);
    }

    private Locator transactionTimeSelect() {
        return searchWrapper().locator("select").nth(1);
    }

    private Locator fromInput() {
        return searchWrapper().locator("input").nth(0);
    }

    private Locator toInput() {
        return searchWrapper().locator("input").nth(1);
    }

    public void selectRecordType(String label) {
        recordTypeSelect().selectOption(new SelectOption().setLabel(label));
        page.waitForTimeout(400);
    }

    public void selectTransactionTime(String label) {
        transactionTimeSelect().selectOption(new SelectOption().setLabel(label));
        page.waitForTimeout(500);
    }

    public void setCustomDate(String fromValue, String toValue) {
        selectTransactionTime("custom date");
        fromInput().fill(fromValue);
        toInput().fill(toValue);
        page.waitForTimeout(300);
    }

    /**
     * The From/To boundary the site is actually about to search with - auto-computed by the site
     * itself for preset time filters (Today, This week, ...), or whatever was typed in for
     * "custom date". Reading it back (rather than re-computing the boundary ourselves) sidesteps
     * any timezone difference between this machine and the site's "Hong Kong Time" clock.
     */
    public LocalDateTime[] getEffectiveRange() {
        LocalDateTime from = LocalDateTime.parse(fromInput().inputValue(), DATE_FORMAT);
        LocalDateTime to = LocalDateTime.parse(toInput().inputValue(), DATE_FORMAT);
        return new LocalDateTime[] {from, to};
    }

    public boolean hasNoMatch() {
        return page.getByText("No match result found.").count() > 0;
    }

    /**
     * The datetime shown in each result row's date column (e.g. "Transaction Date" or, for
     * Betting, "Update Time"), parsed as datetimes - empty if the "No match result found." state
     * is shown.
     */
    public List<LocalDateTime> getRowDates(int dateColumnIndex) {
        List<LocalDateTime> dates = new ArrayList<>();
        if (hasNoMatch()) {
            return dates;
        }
        Locator rows = page.locator(TABLE).locator("tbody tr");
        for (int i = 0; i < rows.count(); i++) {
            String cellText = rows.nth(i).locator("td").nth(dateColumnIndex).innerText().strip();
            dates.add(LocalDateTime.parse(cellText, DATE_FORMAT));
        }
        return dates;
    }

    /** Returns {currentPageAmountText, allRecordsAmountText} read from the table's two "Total ..." footer rows. */
    public String[] getTotals() {
        Locator footerRows = page.locator(TABLE).locator("tfoot tr");
        Locator currentPageCells = footerRows.nth(0).locator("td");
        Locator allRecordsCells = footerRows.nth(1).locator("td");
        return new String[] {
                currentPageCells.nth(2).innerText().strip(),
                allRecordsCells.nth(2).innerText().strip()
        };
    }

    public int rowCount() {
        if (hasNoMatch()) {
            return 0;
        }
        return page.locator(TABLE).locator("tbody tr").count();
    }

    public void clickCheckOnRow(int index) {
        page.locator(CHECK_BUTTON).nth(index).click();
        page.waitForTimeout(800);
    }

    public boolean detailsPopupVisible(int timeoutMs) {
        try {
            page.getByText("Details of record", new Page.GetByTextOptions().setExact(true))
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void closeDetailsPopup() {
        page.getByText("Close", new Page.GetByTextOptions().setExact(true)).click();
        page.waitForTimeout(500);
    }

    public static boolean isAmountText(String text) {
        return AMOUNT_PATTERN.matcher(text.strip()).matches();
    }
}
