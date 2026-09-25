package com.platform.utility;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Talks directly to the admin panel's REST API (not the member-facing site under test) to reject
 * pending withdrawal requests left over from repeated automated test runs. The member-facing
 * Withdrawal page enforces a "Daily withdrawal frequency is 3 times" limit per day, which heavy
 * same-day test runs exhaust quickly; rejecting today's pending requests frees that quota back up
 * immediately instead of waiting for the next calendar day.
 */
public class AdminApiClient {

    private static final String BASE_URL = "https://admin-test.138hk.vip";
    private static final String ACCOUNT = "sak123";
    private static final String PASSWORD = "123456@a";
    private static final String CAPTCHA = "1111";
    private static final String CAPTCHA_KEY = "captcha-7jUYYforUCr3Oq0RFr76";
    private static final int MEMBER_ID = 2584;

    private static final HttpClient CLIENT = HttpClient.newHttpClient();
    private static String cachedToken;

    private AdminApiClient() {
    }

    /**
     * Rejects every pending (status 0) withdrawal request made today for the test account in the
     * given currency, freeing up the daily withdrawal frequency limit that heavy same-day test
     * runs otherwise exhaust. Safe to call when there's nothing pending - it's simply a no-op.
     */
    public static void rejectTodaysPendingWithdrawals(String currency) {
        try {
            String token = token();
            for (int id : findPendingWithdrawalIds(token, currency)) {
                rejectWithdrawal(token, id, currency);
            }
        } catch (Exception e) {
            throw new RuntimeException("Unable to reject pending withdrawals via the admin API", e);
        }
    }

    private static String token() throws IOException, InterruptedException {
        if (cachedToken != null) {
            return cachedToken;
        }
        JsonObject payload = new JsonObject();
        payload.addProperty("account", ACCOUNT);
        payload.addProperty("password", PASSWORD);
        payload.addProperty("captcha", CAPTCHA);
        payload.addProperty("key", CAPTCHA_KEY);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/account/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Admin login failed (HTTP " + response.statusCode() + "): " + response.body());
        }
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        cachedToken = body.get("access_token").getAsString();
        return cachedToken;
    }

    private static List<Integer> findPendingWithdrawalIds(String token, String currency) throws IOException, InterruptedException {
        String today = LocalDate.now().toString();
        String query = "where[type][0]=2"
                + "&where[status][0]=0"
                + "&where[amount_type][0]=0"
                + "&where[amount_type][1]=1"
                + "&where[member_id]=" + MEMBER_ID
                + "&whereRange[date_start]=" + encode(today + " 00:00:00")
                + "&whereRange[date_end]=" + encode(today + " 23:59:59")
                + "&paginate=50"
                + "&page=1"
                + "&currency=" + encode(currency);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/account/order?" + query))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Fetching pending withdrawals failed (HTTP " + response.statusCode() + "): " + response.body());
        }
        JsonObject body = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonArray lists = body.getAsJsonObject("data").getAsJsonArray("lists");
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < lists.size(); i++) {
            ids.add(lists.get(i).getAsJsonObject().get("id").getAsInt());
        }
        return ids;
    }

    private static void rejectWithdrawal(String token, int id, String currency) throws IOException, InterruptedException {
        JsonObject payload = new JsonObject();
        payload.addProperty("public_note", "test");
        payload.addProperty("locate_schedule_id", 1);
        payload.addProperty("currency", currency);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/api/account/ticketing/new-ticket/withdrawal/" + id + "?currency=" + encode(currency)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(payload.toString()))
                .build();
        HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Rejecting withdrawal " + id + " failed (HTTP " + response.statusCode() + "): " + response.body());
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
