package com.example.chat.auth;

import java.time.Instant;

// board 토큰의 claims 중 chat이 쓰는 것만: sub(username), jti(denylist 키), exp
public record TokenClaims(String username, String jti, Instant expiresAt) {
}
