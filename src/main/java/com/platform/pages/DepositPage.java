package com.platform.pages;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.WaitForSelectorState;
import com.microsoft.playwright.options.WaitUntilState;

import java.util.Map;

/**
 * Locators ported from the Python Platform_automation project's pages/depositPage.py, verified
 * against the real Deposit page on https://test-v2.138hk.vip.
 */
public class DepositPage extends BasePage {

    private static final String AMOUNT_INPUT = "input[placeholder*='Min']";
    private static final String SUBMIT_BUTTON = "button.SubmitButton_submit__luKqw";
    private static final String BIND_MODAL = "#main-modal";
    private static final String BIND_FIELD_ITEM = ".ContentTemplate_bank-detail-item__nyYsv";
    private static final String BIND_FIELD_LABEL = ".ContentTemplate_bank-detail-label__LaWPg";

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

    public void enterAmount(int amount) {
        dismissPromoPopup();
        page.locator(AMOUNT_INPUT).fill(String.valueOf(amount));
    }

    public void selectPresetAmount(int amount) {
        dismissPromoPopup();
        page.getByText("Please select", new Page.GetByTextOptions().setExact(true)).first().click();
        page.waitForTimeout(300);
        page.getByText(String.valueOf(amount), new Page.GetByTextOptions().setExact(true)).click();
        page.waitForTimeout(300);
    }

    public boolean isBound() {
        return page.getByText("Click [Add] to bind Banking details").count() == 0;
    }

    /** Binds a single-field account (e.g. a wallet address or phone number). */
    public void bindAccount(String value) {
        if (isBound()) {
            return;
        }
        openBindModal();
        Locator modal = page.locator(BIND_MODAL).last();

        Locator targetInput = modal.locator("input").first();
        targetInput.waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3000));
        targetInput.fill(value);

        submitBindModal(modal);
    }

    /**
     * Binds a multi-field account (e.g. bank transfer/FPS), matched to each field by its visible
     * label. Label-based matching is used instead of field order because some of these forms
     * auto-fill and lock a field (e.g. "Full name") once another field is filled in, and the
     * visible field order differs between option types.
     */
    public void bindAccount(Map<String, String> fieldsByLabel) {
        if (isBound()) {
            return;
        }
        openBindModal();
        Locator modal = page.locator(BIND_MODAL).last();

        Locator fields = modal.locator(BIND_FIELD_ITEM);
        for (int i = 0; i < fields.count(); i++) {
            Locator field = fields.nth(i);
            Locator targetInput = field.locator("input");
            if (targetInput.count() == 0) {
                continue;
            }
            String labelText = field.locator(BIND_FIELD_LABEL).first().innerText();
            for (Map.Entry<String, String> entry : fieldsByLabel.entrySet()) {
                if (labelText.contains(entry.getKey())) {
                    if (!targetInput.first().isDisabled()) {
                        targetInput.first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(3000));
                        targetInput.first().fill(entry.getValue());
                        if (!entry.getValue().equals(targetInput.first().inputValue())) {
                            targetInput.first().fill(entry.getValue());
                        }
                    }
                    break;
                }
            }
        }

        submitBindModal(modal);
    }

    private void openBindModal() {
        dismissPromoPopup();
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Add").setExact(true)).click();
        page.waitForTimeout(1200);

        Locator modal = page.locator(BIND_MODAL).last();
        modal.locator(BIND_FIELD_ITEM).first().waitFor(new Locator.WaitForOptions().setState(WaitForSelectorState.VISIBLE).setTimeout(5000));
        selectDefaultDropdownChoices(modal);
    }

    /** Any dropdown left on its placeholder option (e.g. Bank Name) is defaulted to its first real choice. */
    private void selectDefaultDropdownChoices(Locator modal) {
        Locator dropdowns = modal.locator("select");
        for (int i = 0; i < dropdowns.count(); i++) {
            Locator dropdown = dropdowns.nth(i);
            String current = dropdown.inputValue();
            if (current.equals("-1") || current.isEmpty()) {
                Locator choices = dropdown.locator("option:not([disabled])");
                if (choices.count() > 0) {
                    dropdown.selectOption(choices.first().getAttribute("value"));
                    page.waitForTimeout(300);
                }
            }
        }
    }

    private void submitBindModal(Locator modal) {
        modal.locator("button", new Locator.LocatorOptions().setHasText("SUBMIT")).click();
        page.waitForTimeout(1000);
        confirmPrompt();
        String message = getAlertMessage();
        closeAlert(message);
        dismissAnyAlert();
        waitUntilBound(10000);
    }

    /**
     * The bind modal closing doesn't guarantee the main Bank Name/account dropdown on the
     * deposit form has picked up the newly bound account yet - proceeding straight to submit
     * can land while it's still showing its pre-bind (unbound) state, getting the deposit
     * rejected even though the account really is bound. Wait for isBound() to actually flip
     * before letting a caller continue.
     */
    private void waitUntilBound(int timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (isBound()) {
                return;
            }
            page.waitForTimeout(300);
        }
    }

    public void submit() {
        dismissPromoPopup();
        try {
            page.locator(SUBMIT_BUTTON).click(new Locator.ClickOptions().setTimeout(8000));
            return;
        } catch (Exception ignored) {
        }
        dismissPromoPopup();
        page.locator(SUBMIT_BUTTON).click(new Locator.ClickOptions().setForce(true));
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
