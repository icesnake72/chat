package com.example.chat.support;

import com.example.chat.auth.TokenDenylist;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Redis 없이 membership만 흉내낸다. @Transactional 롤백이 되돌리지 못하므로 @BeforeEach clear() 필요.
public class InMemoryTokenDenylist implements TokenDenylist {

  private final Set<String> denied = ConcurrentHashMap.newKeySet();

  public void deny(String jti) {
    denied.add(jti);
  }

  public void clear() {
    denied.clear();
  }

  @Override
  public boolean isDenied(String jti) {
    return denied.contains(jti);
  }
}
