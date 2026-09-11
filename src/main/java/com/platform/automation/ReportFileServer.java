package com.platform.automation;

import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;

/**
 * Minimal local static file server for the reports/ directory, so a spreadsheet REPORT link can
 * point to an http://localhost URL instead of a file:// path. Browsers commonly block navigating
 * from an https: page (like Google Sheets) to a file: URL when a link is clicked, but allow
 * plain http navigation freely - this sidesteps that restriction entirely.
 */
public class ReportFileServer {

    private final HttpServer server;
    private final int port;

    public ReportFileServer(File reportsDir, int port) {
        this.port = port;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", port), 0);
        } catch (IOException e) {
            throw new RuntimeException("Unable to start local report server on port " + port, e);
        }
        File canonicalReportsDir;
        try {
            canonicalReportsDir = reportsDir.getCanonicalFile();
        } catch (IOException e) {
            throw new RuntimeException("Unable to resolve reports directory: " + reportsDir, e);
        }
        server.createContext("/", exchange -> serveFile(exchange, canonicalReportsDir));
        server.setExecutor(null);
    }

    public void start() {
        server.start();
        System.out.println("Serving reports/ at http://localhost:" + port + "/");
    }

    public String urlFor(String fileName) {
        return "http://localhost:" + port + "/" + fileName;
    }

    private static void serveFile(com.sun.net.httpserver.HttpExchange exchange, File reportsDir) throws IOException {
        String requestedName = exchange.getRequestURI().getPath().replaceFirst("^/", "");
        File file = requestedName.isBlank() ? null : new File(reportsDir, requestedName).getCanonicalFile();

        if (file == null || !file.getPath().startsWith(reportsDir.getPath()) || !file.isFile()) {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
            return;
        }

        byte[] content = Files.readAllBytes(file.toPath());
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, content.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(content);
        }
    }
}
