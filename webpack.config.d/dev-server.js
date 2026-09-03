config.devServer = config.devServer || {};

// Разрешить cloudflare quick tunnel host.
// Без этого webpack-dev-server отвечает "Invalid Host header".
config.devServer.allowedHosts = [
    "localhost",
    "127.0.0.1",
    ".trycloudflare.com"
];

// Не обязательно для Cloudflare Tunnel, но полезно для LAN-тестов.
config.devServer.host = "0.0.0.0";

config.devServer.client = config.devServer.client || {};
config.devServer.client.webSocketURL = "auto://0.0.0.0:0/ws";