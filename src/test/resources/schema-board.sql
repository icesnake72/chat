-- board 프로젝트의 users / user_profiles 를 H2에 흉내낸 픽스처 (읽기 전용 조회 검증용)
CREATE SCHEMA IF NOT EXISTS board;

CREATE TABLE IF NOT EXISTS board.users (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  username VARCHAR(50) NOT NULL UNIQUE,
  email VARCHAR(100) NOT NULL UNIQUE,
  password VARCHAR(255) NOT NULL,
  role VARCHAR(20) NOT NULL,
  provider VARCHAR(20) NOT NULL,
  provider_id VARCHAR(100),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS board.user_profiles (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL UNIQUE,
  nickname VARCHAR(50) NOT NULL UNIQUE,
  bio VARCHAR(500),
  phone_number VARCHAR(20),
  birth_date DATE,
  profile_image_url VARCHAR(500),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL
);

MERGE INTO board.users (id, username, email, password, role, provider, created_at, updated_at)
  KEY (id) VALUES
  (1, 'alice', 'alice@example.com', 'x', 'USER', 'LOCAL', NOW(), NOW()),
  (2, 'noprofile', 'noprofile@example.com', 'x', 'USER', 'LOCAL', NOW(), NOW());

MERGE INTO board.user_profiles (id, user_id, nickname, created_at, updated_at)
  KEY (id) VALUES
  (1, 1, '앨리스', NOW(), NOW());
