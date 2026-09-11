package com.example.chat.auth.dto;

import com.example.chat.auth.ChatPrincipal;

public record MeResponse(Long userId, String username, String nickname) {

  public static MeResponse from(ChatPrincipal principal) {
    return new MeResponse(principal.userId(), principal.username(), principal.nickname());
  }
}
