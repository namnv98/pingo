package com.pingo.chat.grpc;

import io.vertx.grpc.common.GrpcMessageDecoder;
import io.vertx.grpc.common.GrpcMessageEncoder;
import io.vertx.grpc.common.ServiceMethod;
import io.vertx.grpc.common.ServiceName;

/**
 * Định danh RPC {@code Link.Stream} (xem {@code link.proto}, ARCHITECTURE.md mục 12) dưới dạng
 * {@link ServiceMethod} — kiểu dispatch của chính Vert.x (5.x trở đi, xem {@code vertx-grpc-common}),
 * thay cho {@code io.grpc.MethodDescriptor} sinh bởi {@code protoc-gen-grpc-java} như trước
 * (vertx-grpc-server/client 5.x tự thân không còn phụ thuộc grpc-java nữa nên không còn plugin đó
 * để sinh class — xem {@code chat-domain/pom.xml}).
 *
 * <p>{@link #STREAM_SERVER} dùng ở phía nhận RPC ({@code GrpcServer.callHandler}, xem colony's
 * {@code ColonyAppModule}); {@link #STREAM_CLIENT} dùng ở phía gọi RPC ({@code GrpcClient.request},
 * xem harbor's {@code BackendStreamGateway}) — 2 hằng số riêng theo đúng API dự định (server đọc
 * Req/ghi Resp, client ghi Req/đọc Resp), dù cùng kiểu {@code Frame} cả 2 chiều ở RPC này.
 */
public final class LinkService {

  private static final ServiceName SERVICE_NAME = ServiceName.create("com.pingo.chat.grpc", "Link");

  public static final ServiceMethod<Frame, Frame> STREAM_SERVER =
      ServiceMethod.server(SERVICE_NAME, "Stream", GrpcMessageEncoder.encoder(), GrpcMessageDecoder.decoder(Frame.getDefaultInstance()));

  public static final ServiceMethod<Frame, Frame> STREAM_CLIENT =
      ServiceMethod.client(SERVICE_NAME, "Stream", GrpcMessageEncoder.encoder(), GrpcMessageDecoder.decoder(Frame.getDefaultInstance()));

  private LinkService() {}
}
