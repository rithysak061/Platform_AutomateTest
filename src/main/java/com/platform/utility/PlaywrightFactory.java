package com.platform.utility;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

public class PlaywrightFactory {

    private static final ThreadLocal<Playwright> playwrightThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<Browser> browserThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<BrowserContext> contextThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<Page> pageThreadLocal = new ThreadLocal<>();

    public static Page initBrowser() {
        Playwright playwright = Playwright.create();
        playwrightThreadLocal.set(playwright);

        String browserName = ConfigReader.get("browser");
        boolean headless = ConfigReader.getBoolean("headless");
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(headless);

        Browser browser;
        switch (browserName.toLowerCase()) {
            case "firefox":
                browser = playwright.firefox().launch(launchOptions);
                break;
            case "webkit":
                browser = playwright.webkit().launch(launchOptions);
                break;
            default:
                browser = playwright.chromium().launch(launchOptions);
        }
        browserThreadLocal.set(browser);

        BrowserContext context = browser.newContext();
        context.setDefaultTimeout(ConfigReader.getInt("timeout.ms"));
        contextThreadLocal.set(context);

        Page page = context.newPage();
        pageThreadLocal.set(page);
        return page;
    }

    public static Page getPage() {
        return pageThreadLocal.get();
    }

    public static void tearDown() {
        try {
            if (contextThreadLocal.get() != null) {
                contextThreadLocal.get().close();
            }
            if (browserThreadLocal.get() != null) {
                browserThreadLocal.get().close();
            }
            if (playwrightThreadLocal.get() != null) {
                playwrightThreadLocal.get().close();
            }
        } finally {
            pageThreadLocal.remove();
            contextThreadLocal.remove();
            browserThreadLocal.remove();
            playwrightThreadLocal.remove();
        }
    }
}
