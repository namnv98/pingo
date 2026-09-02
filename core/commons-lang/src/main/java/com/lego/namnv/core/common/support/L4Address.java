package com.lego.namnv.core.common.support;

import lombok.*;
import lombok.experimental.Accessors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Accessors(chain = true)
public class L4Address {

    private @NonNull String host;
    private int port;

    @Override
    public String toString() {
        var sb = new StringBuilder().append(host);
        if (port > 0)
            sb.append(':').append(port);
        return sb.toString();
    }
}
