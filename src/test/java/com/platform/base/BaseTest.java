package com.platform.base;

import com.microsoft.playwright.Page;
import com.platform.pages.LoginPage;
import com.platform.utility.ConfigReader;
import com.platform.utility.PlaywrightFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;

public class BaseTest {

    private static final ThreadLocal<Page> currentPage = new ThreadLocal<>();
    private static final ThreadLocal<LoginPage> currentLoginPage = new ThreadLocal<>();
    protected LoginPage loginPage;
    protected String baseUrl;

    @BeforeMethod
    public void setUp() {
        Page page = PlaywrightFactory.initBrowser();
        currentPage.set(page);
        baseUrl = ConfigReader.get("base.url");
        loginPage = new LoginPage(page);
        currentLoginPage.set(loginPage);
        loginPage.open(baseUrl);
    }

    @AfterMethod
    public void tearDown() {
        PlaywrightFactory.tearDown();
        currentPage.remove();
        currentLoginPage.remove();
    }

    public static Page getCurrentPage() {
        return currentPage.get();
    }

    public static LoginPage getCurrentLoginPage() {
        return currentLoginPage.get();
    }
}
