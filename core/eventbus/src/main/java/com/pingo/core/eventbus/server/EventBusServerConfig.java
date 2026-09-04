package com.pingo.core.eventbus.server;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class EventBusServerConfig {
    private String address;
    private String tags;
    private boolean replyBytes = false;
    private boolean errorReplyViaHeader = false;
    private boolean printStackTrace = false;
}
