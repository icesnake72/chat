package com.example.chat.auth;

import com.example.chat.auth.dto.MeResponse;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat")
public class MeController {

  @GetMapping("/me")
  public MeResponse me(@AuthenticationPrincipal ChatPrincipal principal) {
    return MeResponse.from(principal);
  }
}
