package com.pingo.discovery.router;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.net.InetAddress;

@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
@JsonSubTypes({@JsonSubTypes.Type(value = K8sDestination.class, name = "K8sDestination")})
public interface Destination {

  static Destination of(String podName, InetAddress ip) {
    return new K8sDestination(podName, ip);
  }

  static Destination of(String podName) {
    return of(podName, null);
  }

  InetAddress ip();

  String name();
}
