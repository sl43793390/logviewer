//package com.so.controller;
//
//import com.jcraft.jsch.ChannelShell;
//import com.jcraft.jsch.JSch;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.stereotype.Component;
//
//import javax.json.Json;
//import javax.json.JsonObject;
//import javax.websocket.*;
//import javax.websocket.server.ServerEndpoint;
//import java.io.*;
//import java.nio.ByteBuffer;
//import java.nio.charset.StandardCharsets;
//
//@Component
//@ServerEndpoint("/ws/ssh")
//public class SSHWebSocketEndpoint {
//    private com.jcraft.jsch.Session jschSession;
//    private ChannelShell channel;
//    private static final Logger log = LoggerFactory.getLogger(SSHWebSocketEndpoint.class);
//    private PipedOutputStream pipeOut;
//    private Thread outputThread;
//    @OnOpen
//    public void onOpen(Session session) throws Exception {
//        System.out.println("New connection");
//        JSch jsch = new JSch();
//        jschSession = jsch.getSession("root", "192.168.80.151", 22);
//        jschSession.setPassword("test");
//        jschSession.setConfig("StrictHostKeyChecking", "no");
//        jschSession.connect();
//
//        channel = (ChannelShell) jschSession.openChannel("shell");
//        channel.setPtyType("vt100");
//        channel.connect();
//
//        new Thread(() -> {
//            try (InputStream in = channel.getInputStream()) {
//                int b;
//                while ((b = in.read()) != -1) {
//                    log.info("Received message: " + (char) b);
//                    session.getBasicRemote().sendText(String.valueOf((char) b));
//                }
//            } catch (Exception e) {
//               log.error("Error reading from SSH channel:", e);
//               e.printStackTrace();
//            }
//        }).start();
//    }
//
//    @OnMessage
//    public void onMessage(String message, Session session) throws Exception {
//        JsonObject json = Json.createReader(new StringReader(message)).readObject();
//        String input = json.getString("data");
//        log.info("Received message: " + input);
//        channel.getOutputStream().write(input.getBytes());
//        channel.getOutputStream().flush();
//    }
//
//    // 回车换行转换
//    private byte[] convertLineEndings(byte[] input) {
//        String str = new String(input, StandardCharsets.UTF_8);
//        str = str.replace("\r", "\r\n"); // CR 转 CRLF
//        return str.getBytes(StandardCharsets.UTF_8);
//    }
//
//    private boolean containsCarriageReturn(byte[] bytes) {
//        for (byte b : bytes) {
//            if (b == '\r') return true;
//        }
//        return false;
//    }
//
//    // 资源关闭方法
//    private void closeResources(Session session) {
//        try {
//            if (pipeOut != null) pipeOut.close();
//            if (channel != null) channel.disconnect();
//            if (jschSession != null) jschSession.disconnect();
//            if (session != null) session.close();
//        } catch (IOException e) {
//            log.error("Resource close error", e);
//        }
//    }
//
//    @OnClose
//    public void onClose() {
//        System.out.println("Connection closed");
//        if (channel != null) channel.disconnect();
//        if (jschSession != null) jschSession.disconnect();
//    }
//    @OnError
//    public void onError(Session session, Throwable throwable) {
//        throwable.printStackTrace();
//    }
//}