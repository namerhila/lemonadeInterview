package com.hila.lemonadeinterview;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Local HTTP server (JDK built-in) that serves fixture bodies to the background job.
 * Built-in paths: /slow (answers after 3s), /404, /broken (closes mid-body).
 */
class TestFileServer implements AutoCloseable {

    private final HttpServer server;

    TestFileServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/slow", exchange -> {
            sleep(3000);
            byte[] body = "slowword".getBytes(UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.createContext("/404", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        server.createContext("/broken", exchange -> {
            exchange.sendResponseHeaders(200, 1000);
            OutputStream out = exchange.getResponseBody();
            out.write("brokenword brokenword ".getBytes(UTF_8));
            out.flush();
            // Throwing from the handler makes the server close the socket mid-body.
            throw new IOException("simulated connection drop");
        });
        server.start();
    }

    /** Serves the body at the path and returns its full URL. */
    String serve(String path, byte[] body) {
        server.createContext(path, exchange -> {
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        return url(path);
    }

    String serve(String path, String text) {
        return serve(path, text.getBytes(UTF_8));
    }

    String url(String path) {
        return "http://localhost:" + server.getAddress().getPort() + path;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
