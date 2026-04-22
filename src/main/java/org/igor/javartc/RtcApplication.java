package org.igor.javartc;

import org.freedesktop.gstreamer.Gst;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import jakarta.annotation.PreDestroy;

@SpringBootApplication
@EnableWebSocket
public class RtcApplication implements WebSocketConfigurer {

    @Autowired private WebSocketHandler rtcHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(rtcHandler, "/rtc").setAllowedOrigins("*");
    }

    @PreDestroy
    public void cleanup() {
        Gst.deinit();
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(RtcApplication.class);
        app.setHeadless(false);
        app.run(args);
    }
}
