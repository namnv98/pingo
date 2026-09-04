package com.pingo.core.common.support;

import lombok.*;
import lombok.experimental.Accessors;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Accessors(chain = true)
public class L6Address {
    private @NonNull String host;
    private int port;
    private boolean useSsl;

    @Override
    public String toString() {
        var sb = new StringBuilder();
        if (useSsl)
            sb.append("ssl:");
        sb.append(host);
        if (port > 0)
            sb.append(':').append(port);
        return sb.toString();
    }
}
