package com.example.chat.room;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

  boolean existsByName(String name);

  Page<ChatRoom> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
