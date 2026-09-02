package com.lego.namnv.core.eventbus;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.eventbus.Message;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.extern.log4j.Log4j2;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;
import java.math.BigInteger;

@Log4j2
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class EventbusUtils {

    public static @Nullable Buffer getBuffer(Message<?> message) {
        var body = message.body();
        if (body == null) {
            return null;
        }
        if (body instanceof Buffer buff)
            return buff;

        if (body instanceof byte[] bytes)
            return Buffer.buffer(bytes);

        if (body instanceof String str) {
            copyMemWarning(body.getClass());
            return Buffer.buffer(str);
        }

        if (body instanceof Number num) {
            copyMemWarning(body.getClass());
            return numberToBuffer(num);
        }

        if (body instanceof JsonObject jo) {
            copyMemWarning(body.getClass());
            return jo.toBuffer();
        }

        if (body instanceof JsonArray arr) {
            copyMemWarning(body.getClass());
            return arr.toBuffer();
        }

        throw new RuntimeException("UnsupportedResponseException(body)");
    }

    private static Buffer numberToBuffer(Number number) {
        if (number instanceof Byte b)
            return Buffer.buffer(1).appendByte(b);
        if (number instanceof Short s)
            return Buffer.buffer(2).appendShort(s);
        if (number instanceof Integer i)
            return Buffer.buffer(4).appendInt(i);
        if (number instanceof Long l)
            return Buffer.buffer(8).appendLong(l);
        if (number instanceof Float f)
            return Buffer.buffer(4).appendFloat(f);
        if (number instanceof Double d)
            return Buffer.buffer(8).appendDouble(d);
        if (number instanceof BigInteger bi)
            return Buffer.buffer(bi.toByteArray());
        throw new RuntimeException("UnsupportedResponseException(body)");
    }

    private static void copyMemWarning(Class<?> type) {
        var text = "EventBus client prefer response value type is either byte[] or Buffer, current type `{}` is accepted but can make significant mem copying cost";
        log.warn(text, type.getName());
    }

}
