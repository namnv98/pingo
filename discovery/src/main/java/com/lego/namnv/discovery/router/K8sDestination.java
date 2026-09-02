package com.lego.namnv.discovery.router;

import java.io.Serial;
import java.io.Serializable;
import java.net.InetAddress;
import java.util.Objects;
import lombok.*;

@ToString
@AllArgsConstructor
@NoArgsConstructor
@Getter
public class K8sDestination implements Destination , Serializable {
  @Serial
  private static final long serialVersionUID = 5580878942888352161L;

  private String name;
  private InetAddress ip;

  public InetAddress ip() {
    return ip;
  }

  public @NonNull String name() {
    return name;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) return true;
    if (obj == null || obj.getClass() != this.getClass()) return false;
    var that = (K8sDestination) obj;
    return Objects.equals(this.name, that.name);
  }
}
