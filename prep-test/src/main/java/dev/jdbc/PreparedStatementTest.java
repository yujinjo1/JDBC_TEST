package dev.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * PreparedStatement의 MySQL 서버 사이드 캐싱 동작을 프로파일링 로그, 내부 객체 ID(Unwrap), 
 * 그리고 실제 DB의 Session Status를 통해 완벽하게 교차 검증하는 예시
 */


public class PreparedStatementTest {
	// DBMS 서버 접속 정보
	private static final String USER_NAME = "";
	private static final String PASSWORD = "";
	private static final String DB_URL = "jdbc:mysql://localhost:3306/";
	private static final String DATABASE_SCHEMA = "sakila";

	public static void main(String[] args) {
		System.out.println("=======================================================");
		System.out.println("시나리오 1: ClientPreparedStatement");
		System.out.println("옵션: useServerPrepStmts=false");
		System.out.println("=======================================================");
		runTest("?useServerPrepStmts=false&profileSQL=true&logger=com.mysql.cj.log.StandardLogger");

		System.out.println("\n=======================================================");
		System.out.println("시나리오 2: ServerPreparedStatement (캐시 끔 - 비효율적)");
		System.out.println("옵션: useServerPrepStmts=true & cachePrepStmts=false");
		System.out.println("=======================================================");
		runTest("?useServerPrepStmts=true&cachePrepStmts=false&profileSQL=true&logger=com.mysql.cj.log.StandardLogger");

		System.out.println("\n=======================================================");
		System.out.println("시나리오 3: ServerPreparedStatement (캐시 켬 - 가장 이상적)");
		System.out.println("옵션: useServerPrepStmts=true & cachePrepStmts=true");
		System.out.println("=======================================================");
		runTest("?useServerPrepStmts=true&cachePrepStmts=true&profileSQL=true&logger=com.mysql.cj.log.StandardLogger");
	}

	private static void runTest(String jdbcOptions) {
		// DB 연결 URL 조립
		String url = DB_URL + DATABASE_SCHEMA + jdbcOptions;
		String[] testNames = { "MARY", "PATRICIA", "LINDA" };

		try (Connection connection = DriverManager.getConnection(url, USER_NAME, PASSWORD)) {

			System.out.println("  [테스트 시작 전]");
			checkServerStatementCount(connection);

			for (int i = 0; i < 3; i++) {
				System.out.println("\n--- [반복 " + (i + 1) + "] 애플리케이션에서 '" + testNames[i] + "' 조회 요청 ---");

				String sql = "SELECT * FROM customer WHERE first_name = ?";

				try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
					pstmt.setString(1, testNames[i]); // 입력값을 바인딩

					try (ResultSet rs = pstmt.executeQuery()) {
						if (rs.next()) {
							System.out
									.print("-> 조회 성공: " + rs.getString("first_name") + " " + rs.getString("last_name"));

							// :fire: [핵심 1] JDBC 껍데기를 벗겨내고 MySQL 전용 객체에서 진짜 ID 추출
							if (pstmt.isWrapperFor(com.mysql.cj.jdbc.ServerPreparedStatement.class)) {
								com.mysql.cj.jdbc.ServerPreparedStatement mysqlStmt = pstmt
										.unwrap(com.mysql.cj.jdbc.ServerPreparedStatement.class);
								System.out.println("  (숨겨진 진짜 Statement ID: " + mysqlStmt.getServerStatementId() + ")");
							} else {
								System.out.println("  (이 객체는 ServerPreparedStatement가 아닙니다. ID 없음)");
							}
						} else {
							System.out.println("-> 조회 결과 없음");
						}
					}
				} // :bulb: try-with-resources에 의해 여기서 pstmt.close()가 자동 호출됩니다!

				// :fire: [핵심 2] close() 호출 직후, DB 서버에 캐시가 진짜 살아있는지 카운트 확인
				checkServerStatementCount(connection);
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}

	/**
	 * 현재 MySQL 세션에 살아있는 Prepared Statement의 개수를 조회합니다.
	 */
	private static void checkServerStatementCount(Connection conn) {
		String statusSql = "SHOW SESSION STATUS LIKE 'Prepared_stmt_count'";
		// 준비된 구문 자체가 카운트를 올리지 않도록 순수 Statement 사용
		try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(statusSql)) {
			if (rs.next()) {
				System.out.println("    => DB 서버 내 캐시된 Statement 개수: " + rs.getInt("Value"));
			}
		} catch (SQLException e) {
			e.printStackTrace();
		}
	}
}