package io.example;

import jetbrains.exodus.env.Environment;
import jetbrains.exodus.env.Environments;

import java.io.IOException;

public class App {
    public static void main(String[] args) throws IOException, InterruptedException {
        try (Environment env = Environments.newInstance("tmp/storage" + args[0])) {
            var app = new KVStoreApp(env);
            var server = new GrpcServer(app, Integer.parseInt(args[0]));
            server.start();
            server.blockUntilShutdown();
        }
    }
}
