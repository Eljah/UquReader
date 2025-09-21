package com.example.uqureader.webapp;

import com.sun.net.httpserver.HttpServer;

/**
 * Entry point for the web module.
 * <p>
 *     Without arguments the application prints the placeholder markup to STDOUT.
 *     When launched with {@code --serve [port]} it starts an HTTP endpoint returning
 *     the same markup for any request body.
 * </p>
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        MorphologyService service = new MorphologyService();
        if (args.length > 0 && "--serve".equals(args[0])) {
            int port = args.length > 1 ? Integer.parseInt(args[1]) : 8080;
            WebMorphologyApplication application = new WebMorphologyApplication(service);
            HttpServer server = application.start(port);
            System.out.printf("Server started on port %d%n", server.getAddress().getPort());
            System.out.flush();
        } else {
            System.out.print(service.getMarkup());
        }
    }
}
