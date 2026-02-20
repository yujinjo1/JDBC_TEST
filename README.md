# JDBC PreparedStatement 캐싱 테스트

MySQL `PreparedStatement`의 서버 사이드 캐싱 동작을 검증하는 프로젝트입니다.

## 핵심 개념

PreparedStatement 캐싱을 활성화하면, **최초 1회만 SQL을 PREPARE**하고 이후에는 **Statement ID + 바인딩 값만 전송**하여 쿼리를 실행합니다.

```
[최초 요청]  SQL 전체 전송 → PREPARE → Statement ID 발급

[이후 요청]  Statement ID + 바인딩 값만 전송 → 바로 실행
```

이를 통해 SQL 파싱 비용을 줄이고, 네트워크 전송량도 감소시킵니다.

## 테스트 시나리오

### 시나리오 1: ClientPreparedStatement (`useServerPrepStmts=false`)

- JDBC 드라이버가 클라이언트 측에서 `?`에 값을 직접 치환하여 **완성된 SQL 문자열**을 매번 서버에 전송
- 서버는 매번 SQL을 새로 파싱 → Statement ID가 생성되지 않음
- `Unwrap` 시 `ServerPreparedStatement`가 아니므로 ID를 꺼낼 수 없음
- `Prepared_stmt_count`: 항상 0

### 시나리오 2: ServerPreparedStatement, 캐시 비활성화 (`useServerPrepStmts=true`, `cachePrepStmts=false`)

- 서버에 `PREPARE` 요청 → **DBMS가 Statement ID를 발급**하여 실행 계획을 관리
- 이후 실행 시 ID + 바인딩 값만 전송하여 쿼리 수행
- 하지만 `close()` 호출 시 즉시 `DEALLOCATE PREPARE` → **서버에서 캐시 삭제**
- 다음 반복에서 같은 SQL이어도 다시 PREPARE → 새로운 ID 발급 (비효율적)
- `Prepared_stmt_count`: PREPARE 후 1 → close 후 0 반복
- `Unwrap`으로 `ServerPreparedStatement`에서 `getServerStatementId()` 확인 시 매번 ID가 달라짐

### 시나리오 3: ServerPreparedStatement, 캐시 활성화 (`useServerPrepStmts=true`, `cachePrepStmts=true`)

- 최초 1회만 `PREPARE` → DBMS가 **Statement ID를 발급**
- `close()` 호출 시 `DEALLOCATE`하지 않고 **클라이언트 캐시에 보관**
- 다음 반복에서 같은 SQL이면 캐시에서 꺼내서 **ID + 바인딩 값만 전송** → 재파싱 없음
- `Prepared_stmt_count`: 1로 유지 (서버에 캐시가 계속 살아있음)
- `Unwrap`으로 확인 시 **동일한 Statement ID**가 반복 사용됨

### 시나리오 비교 요약

| 비교 항목 | 시나리오 1 | 시나리오 2 | 시나리오 3 |
|-----------|-----------|-----------|-----------|
| 서버 PREPARE 여부 | X | O | O (최초 1회) |
| Statement ID 발급 | X | 매번 새로 발급 | 최초 1회 발급, 재사용 |
| close() 시 DEALLOCATE | - | 즉시 DEALLOCATE | DEALLOCATE 안 함 (캐시 보관) |
| Prepared_stmt_count | 0 | 0 ↔ 1 반복 | 1 유지 |
| 네트워크 전송 | 완성된 SQL 전체 | ID + 바인딩 값 | ID + 바인딩 값 |
| SQL 재파싱 | 매번 | 매번 | 최초 1회만 |

## 검증 방법

- **`profileSQL=true`**: JDBC 내부 로그를 출력하여 PREPARE/EXECUTE/DEALLOCATE 호출 흐름 확인
- **`Unwrap`**: JDBC 래퍼를 벗겨내고 `ServerPreparedStatement`의 `getServerStatementId()`로 실제 Statement ID 추출
- **`SHOW SESSION STATUS LIKE 'Prepared_stmt_count'`**: 현재 세션에 살아있는 Prepared Statement 개수를 DB에 직접 질의

## 기술 스택

- Java
- MySQL 8.x (sakila 샘플 데이터베이스)
- MySQL Connector/J 8.3.0
- Maven

## 실행 방법

1. MySQL에 [sakila](https://dev.mysql.com/doc/sakila/en/) 샘플 DB 설치
2. `PreparedStatementTest.java`에서 DB 접속 정보 수정
   ```java
   private static final String USER_NAME = "your_username";
   private static final String PASSWORD = "your_password";
   ```
3. 빌드 및 실행
   ```bash
   cd prep-test
   mvn compile exec:java -Dexec.mainClass="dev.jdbc.PreparedStatementTest"
   ```
