package com.example.chat.auth;

// board가 로그아웃 시 등록하는 deny:{jti} 를 읽기만 한다. chat은 이 키를 쓰지 않는다.
public interface TokenDenylist {

  boolean isDenied(String jti);
}
