package com.example.chat.message.dto;

import java.util.List;

// messages는 오래된 순. nextBefore는 다음 페이지 요청의 ?before= 값 (가장 오래된 id).
public record MessagePageResponse(List<MessageResponse> messages, boolean hasMore, Long nextBefore) {
}
