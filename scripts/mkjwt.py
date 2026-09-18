#!/usr/bin/env python3
"""board와 같은 형식(HS256, sub/jti/iat/exp)의 access token을 만든다 — 로컬 검증 전용.

  JWT_SECRET=<base64> python3 scripts/mkjwt.py <username> [ttl_seconds]

JWT_SECRET 이 board와 같으면 board 로그인으로 받은 토큰과 구별되지 않는다.
표준 라이브러리만 쓴다 (PyJWT 불필요).
"""
from __future__ import annotations

import base64
import hashlib
import hmac
import json
import os
import sys
import time
import uuid


def b64url(data: bytes) -> str:
  return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def main() -> int:
  secret_b64 = os.environ.get("JWT_SECRET")
  if not secret_b64:
    print("JWT_SECRET 환경변수가 필요합니다 (.env 참고)", file=sys.stderr)
    return 1
  username = sys.argv[1] if len(sys.argv) > 1 else "admin"
  ttl = int(sys.argv[2]) if len(sys.argv) > 2 else 3600
  now = int(time.time())
  header = b64url(json.dumps({"alg": "HS256"}, separators=(",", ":")).encode())
  payload = b64url(json.dumps(
      {"sub": username, "jti": str(uuid.uuid4()), "iat": now, "exp": now + ttl},
      separators=(",", ":")).encode())
  signature = b64url(hmac.new(
      base64.b64decode(secret_b64), f"{header}.{payload}".encode(), hashlib.sha256).digest())
  print(f"{header}.{payload}.{signature}")
  return 0


if __name__ == "__main__":
  sys.exit(main())
