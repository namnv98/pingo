package com.lego.harbor.ws.dto;

/**
 * Các frame builder nhỏ, dùng chung giữa {@code com.lego.harbor.ws.SockjsSocketManager} và
 * {@code com.lego.harbor.ws.backend.BackendLinkGateway}.
 */
public class SocketFrames {

    private SocketFrames() {
    }

    public static SocketFrame pong(String id) {
        return SocketFrame.builder().type(MessageType.PONG).id(id).ts(now()).build();
    }

    public static SocketFrame ping(String id) {
        return SocketFrame.builder().type(MessageType.PING).id(id).ts(now()).build();
    }

    public static SocketFrame error(String id, String reason) {
        return SocketFrame.builder().type(MessageType.ERROR).id(id).reason(reason).ts(now()).build();
    }

    public static SocketFrame authError(String id, String reason) {
        return SocketFrame.builder().type(MessageType.AUTH_ERROR).id(id).reason(reason).ts(now()).build();
    }

    public static SocketFrame subscribeOk(String id, String conversationId) {
        return SocketFrame.builder().type(MessageType.SUBSCRIBE_OK).id(id).conversationId(conversationId).ts(now()).build();
    }

    public static SocketFrame subscribeError(String id, String reason) {
        return SocketFrame.builder().type(MessageType.SUBSCRIBE_ERROR).id(id).reason(reason).ts(now()).build();
    }

    private static long now() {
        return System.currentTimeMillis();
    }
}
