package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SelectOption;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * Locators verified against the real Withdrawal page on https://test-v2.138hk.vip. Built on the
 * same form components as DepositPage - the channel/option selectors, amount input, submit
 * button, and bind-account modal are all identical (see BasePage) - with two differences: an
 * extra Wallet selector (always "Main Balance" here, since that's the only funding source these
 * tests use), and some options using a fixed-denomination "Withdrawal Game Points" dropdown
 * instead of a free-text amount (same pattern as DepositPage.selectPresetAmount, just scoped to
 * avoid colliding with the Wallet field's identical placeholder text).
 */
public class WithdrawPage extends BasePage {

    public WithdrawPage(Page page) {
        super(page);
    }

    public void open() {
        for (int attempt = 0; attempt < 3; attempt++) {
            page.getByText("Withdrawal", new Page.GetByTextOptions().setExact(true)).first().click();
            try {
                page.waitForURL("**/member/withdrawal/**",
                        new Page.WaitForURLOptions().setTimeout(6000).setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                break;
            } catch (RuntimeException e) {
                if (attempt == 2) {
                    throw e;
                }
            }
        }
        page.waitForTimeout(1000);
    }

    public void selectChannel(String channel) {
        page.getByText(channel, new Page.GetByTextOptions().setExact(true)).click();
        page.waitForTimeout(800);
    }

    public void selectOption(String option) {
        page.getByText(option, new Page.GetByTextOptions().setExact(true)).last().click();
        page.waitForTimeout(800);
    }

    /** Every withdraw option needs a wallet chosen; "Main Balance" is the only real choice. */
    public void selectWallet(String wallet) {
        page.locator("select").first().selectOption(new SelectOption().setLabel(wallet));
        page.waitForTimeout(300);
    }

    /**
     * For options with a fixed-denomination Withdrawal Game Points dropdown (ATM Machine, Cash
     * Payment, Jockey Club Cash Vouchers, Visa Gift Card) instead of a free-text amount. Targets
     * the first *visible* "Please select" match rather than simply the first or last one in DOM
     * order: the page can carry other same-text matches that aren't actually on screen - the
     * Wallet dropdown's own placeholder option before a wallet is chosen, or (after an account is
     * bound) a hidden native &lt;select&gt;'s placeholder &lt;option&gt; sorting after the real,
     * visible custom dropdown - either of which .first()/.last() can pick instead.
     */
    public void selectGamePoints(int amount) {
        dismissPromoPopup();
        Locator matches = page.getByText("Please select", new Page.GetByTextOptions().setExact(true));
        Locator target = null;
        for (int i = 0; i < matches.count(); i++) {
            if (matches.nth(i).isVisible()) {
                target = matches.nth(i);
                break;
            }
        }
        if (target == null) {
            throw new IllegalStateException("No visible \"Please select\" dropdown found for Withdrawal Game Points.");
        }
        target.click();
        page.waitForTimeout(300);
        page.getByText(String.valueOf(amount), new Page.GetByTextOptions().setExact(true)).click();
        page.waitForTimeout(300);
    }

    public boolean withdrawConfirmationVisible() {
        return withdrawConfirmationVisible(8000);
    }

    public boolean withdrawConfirmationVisible(int timeoutMs) {
        try {
            page.getByText("Confirm Withdrawal?").first()
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
