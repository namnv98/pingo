package com.lego.namnv.connector;

import com.lego.namnv.core.message.request.LegoRequest;
import com.lego.namnv.discovery.router.RoutingKey;
import io.vertx.core.buffer.Buffer;
import java.util.Map;
import java.util.UUID;

import lombok.*;

import static java.util.Objects.isNull;

@Builder
@Setter
@Getter
public class RouteByConversationIdRequest implements LegoRequest<Buffer>, RoutingKey {
  public static final String HEADER_CONVERSATION_ID = "conversationId";

  private final UUID conversationId;
  private Map<String, String> params;
  private Map<String, String> headers;
  private Buffer body;

  @Override
  public String getParam(String name) {
    return isNull(params) ? null : params.get(name);
  }

  @Override
  public Iterable<Map.Entry<String, String>> getParams() {
    return isNull(params) ? null : params.entrySet();
  }

  @Override
  public String getHeader(String name) {
    return isNull(headers) ? null : headers.get(name);
  }

  @Override
  public Iterable<Map.Entry<String, String>> getHeaders() {
    return isNull(headers) ? null : headers.entrySet();
  }

  @Override
  public int hash() {
    return UUIDsHashHelper.getInstance().hash(conversationId);
  }
}
