package com.platform.utility;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import java.util.List;

public class PlaywrightFactory {

    private static final ThreadLocal<Playwright> playwrightThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<Browser> browserThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<BrowserContext> contextThreadLocal = new ThreadLocal<>();
    private static final ThreadLocal<Page> pageThreadLocal = new ThreadLocal<>();

    public static Page initBrowser() {
        Playwright playwright = Playwright.create();
        playwrightThreadLocal.set(playwright);

        String browserName = ConfigReader.get("browser").toLowerCase();
        boolean headless = ConfigReader.getBoolean("headless");
        BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions().setHeadless(headless);
        // Headless has no window to maximize; the flag is Chromium-specific.
        boolean maximizeWindow = !headless && browserName.equals("chromium");
        if (maximizeWindow) {
            launchOptions.setArgs(List.of("--start-maximized"));
        }

        Browser browser;
        switch (browserName) {
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

        // --start-maximized only maximizes the OS window; Playwright still forces a fixed
        // viewport (1280x720 by default) inside it unless the viewport is explicitly disabled,
        // which leaves the page rendering at that fixed size despite the window being maximized.
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions();
        if (maximizeWindow) {
            contextOptions.setViewportSize(null);
        }
        BrowserContext context = browser.newContext(contextOptions);
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
