package dev.flyboot.web;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The JDK HTTP server with one virtual thread per request; the flies of the brain pool are the real limit. */
public final class FlyWebServer implements AutoCloseable {

    private final HttpServer server;
    private final ExecutorService executor;

    private FlyWebServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    /** @param address port 0 picks a free port */
    public static FlyWebServer start(InetSocketAddress address, HttpHandler handler) throws IOException {
        HttpServer server = HttpServer.create(address, 0);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", handler);
        server.start();
        return new FlyWebServer(server, executor);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public InetSocketAddress address() {
        return server.getAddress();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.close();
    }
}
