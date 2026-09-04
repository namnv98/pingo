package com.lego.harbor.ws.dto;

import com.lego.namnv.discovery.grpc.Frame;
import com.lego.namnv.discovery.grpc.FrameType;
import io.vertx.core.json.Json;

/**
 * Các frame builder nhỏ, dùng chung giữa {@code com.lego.harbor.ws.HarborSessionManager} và
 * {@code com.lego.harbor.ws.backend.BackendStreamGateway} — bao gồm cả việc dịch qua lại giữa
 * {@link Frame} (gRPC, chặng harbor↔colony) và {@link SocketFrame} (JSON, chặng client↔harbor).
 */
public class SocketFrames {

    private SocketFrames() {
    }

    /** Dịch 1 {@link Frame} nhận từ backend (colony) sang {@link SocketFrame} để relay cho client. */
    public static SocketFrame fromBackendFrame(Frame frame) {
        return SocketFrame.builder()
                .id(frame.getId())
                .type(mapType(frame.getType()))
                .fromUserId(emptyToNull(frame.getFromUserId()))
                .conversationId(emptyToNull(frame.getConversationId()))
                .body(frame.getBodyJson().isEmpty() ? null : Json.decodeValue(frame.getBodyJson()))
                .reason(emptyToNull(frame.getReason()))
                .ts(frame.getTs())
                .build();
    }

    /** Encode body tuỳ ý (payload JSON) thành chuỗi để gán vào {@link Frame#getBodyJson()} khi gửi đi backend. */
    public static String encodeBackendBody(Object body) {
        return body == null ? "" : Json.encode(body);
    }

    private static MessageType mapType(FrameType type) {
        return switch (type) {
            case SUBSCRIBE_OK -> MessageType.SUBSCRIBE_OK;
            case SUBSCRIBE_ERROR -> MessageType.SUBSCRIBE_ERROR;
            case MESSAGE -> MessageType.MESSAGE;
            case ACK -> MessageType.ACK;
            case ERROR -> MessageType.ERROR;
            default -> MessageType.ERROR;
        };
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
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
