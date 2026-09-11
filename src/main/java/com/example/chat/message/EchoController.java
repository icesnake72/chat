package com.example.chat.message;

import com.example.chat.message.dto.EchoRequest;
import com.example.chat.message.dto.EchoResponse;
import java.security.Principal;
import java.time.Instant;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

// 1일차 검증용. Principal은 CONNECT에서 accessor.setUser로 심은 Authentication이라 getName()=username.
// 2일차에 방 단위 메시지(ChatMessageController)로 대체된다.
@Controller
public class EchoController {

  @MessageMapping("/echo")
  @SendTo("/topic/echo")
  public EchoResponse echo(EchoRequest request, Principal principal) {
    return new EchoResponse(principal.getName(), request.content(), Instant.now());
  }
}
