package com.example.chat.room.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RoomCreateRequest(
    @NotBlank @Size(max = 50) String name,
    @Size(max = 200) String description
) {
}
