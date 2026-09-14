package com.example.chat.room;

import com.example.chat.auth.ChatPrincipal;
import com.example.chat.room.dto.MemberResponse;
import com.example.chat.room.dto.RoomCreateRequest;
import com.example.chat.room.dto.RoomResponse;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

  private final ChatRoomService chatRoomService;

  @GetMapping
  public PagedModel<RoomResponse> list(@PageableDefault(size = 20) Pageable pageable) {
    return new PagedModel<>(chatRoomService.list(pageable));
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoomResponse create(
      @Valid @RequestBody RoomCreateRequest request,
      @AuthenticationPrincipal ChatPrincipal principal) {
    return chatRoomService.create(request, principal);
  }

  @GetMapping("/{id}")
  public RoomResponse get(@PathVariable Long id) {
    return chatRoomService.get(id);
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("@roomSecurity.isOwner(#id, principal)")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable Long id) {
    chatRoomService.delete(id);
  }

  @PostMapping("/{id}/join")
  public RoomResponse join(@PathVariable Long id, @AuthenticationPrincipal ChatPrincipal principal) {
    return chatRoomService.join(id, principal);
  }

  @DeleteMapping("/{id}/leave")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void leave(@PathVariable Long id, @AuthenticationPrincipal ChatPrincipal principal) {
    chatRoomService.leave(id, principal);
  }

  @GetMapping("/{id}/members")
  public List<MemberResponse> members(
      @PathVariable Long id, @AuthenticationPrincipal ChatPrincipal principal) {
    return chatRoomService.members(id, principal);
  }
}
