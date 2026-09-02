package com.lego.namnv.discovery.k8s;

import lombok.*;

@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class K8sClientConfig {
    private String namespace;
    private String labelKey;
    private String labelValue;
}
