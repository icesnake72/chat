package com.example.chat.message.dto;

import java.time.Instant;

public record EchoResponse(String sender, String content, Instant sentAt) {
}
