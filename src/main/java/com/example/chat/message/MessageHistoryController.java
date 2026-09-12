package com.example.chat.message;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.message.dto.MessagePageResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat/rooms/{roomId}/messages")
@RequiredArgsConstructor
public class MessageHistoryController {

  private final ChatMessageService chatMessageService;

  @GetMapping
  public MessagePageResponse history(
      @PathVariable Long roomId,
      @RequestParam(required = false) Long before,
      @RequestParam(defaultValue = "50") int size,
      @AuthenticationPrincipal ChatPrincipal principal) {
    return chatMessageService.history(roomId, principal, before, size);
  }
}
