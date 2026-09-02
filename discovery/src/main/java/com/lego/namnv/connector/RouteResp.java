package com.lego.namnv.connector;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class RouteResp {
  private int version;
  private String ip;
  private String podName;
}
