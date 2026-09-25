package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

/**
 * Locators ported from the Python Platform_automation project's pages/depositPage.py, verified
 * against the real Deposit page on https://test-v2.138hk.vip. Amount entry, submit, and
 * bind-account handling are shared with WithdrawPage via BasePage - both pages build on the same
 * form components.
 */
public class DepositPage extends BasePage {

    public DepositPage(Page page) {
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

    public void selectPresetAmount(int amount) {
        dismissPromoPopup();
        page.getByText("Please select", new Page.GetByTextOptions().setExact(true)).first().click();
        page.waitForTimeout(300);
        page.getByText(String.valueOf(amount), new Page.GetByTextOptions().setExact(true)).click();
        page.waitForTimeout(300);
    }

    public boolean depositConfirmationVisible() {
        return depositConfirmationVisible(8000);
    }

    public boolean depositConfirmationVisible(int timeoutMs) {
        try {
            page.getByText("Confirm Deposit?").first()
                    .waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(timeoutMs));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
