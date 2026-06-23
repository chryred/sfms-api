package com.sfms.api.encoding;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 게이트 ① / ①-B: 실제 Oracle 19c(US7ASCII) 왕복 무손실 검증. (DESIGN §4, §4.5)
 *
 * 이것이 "특정 NLS 레시피를 단정하지 않고 검증한다"의 실체다.
 * 0x80-0xFF 바이트가 VARCHAR2/CLOB을 무손실 통과하는지 환경에서 직접 확인한다.
 *
 * ⚠️ 실행 전 필수:
 *   1) 아래 JDBC_URL/USER/PASSWORD를 테스트 DB로 채울 것 (운영 직접 연결 금지).
 *   2) 확정해야 할 설정 표면: NLS_LANG, ojdbc11 버전, -Dfile.encoding=UTF-8, connection properties.
 *   3) 통과 후에만 @Disabled 제거하고 CI 게이트에 편입.
 *
 * 별도로, 기존 운영 실데이터 표본 왕복(§6-1)은 read-only 쿼리로 별도 검증한다.
 */
@Disabled("게이트 미실행: 테스트 DB 접속정보 입력 후 활성화 (DESIGN §4 / §4.5)")
class EncodingRoundTripIT {

    // TODO: 테스트 전용 Oracle 19c(US7ASCII) 접속정보로 교체
    private static final String JDBC_URL = "jdbc:oracle:thin:@//HOST:1521/SERVICE";
    private static final String USER = "TODO";
    private static final String PASSWORD = "TODO";

    private static final String KO = "시설물 점검 이력 (엘리베이터, 소방설비)";

    @Test
    void varchar2_roundTripsKoreanLossless() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE enc_gate_v (id NUMBER, val VARCHAR2(400))");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO enc_gate_v VALUES (1, ?)")) {
                ps.setString(1, EncodingConverter.toDb(KO));   // 핸들러와 동일 경로
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT val FROM enc_gate_v WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(KO, EncodingConverter.fromDb(rs.getString(1)), "VARCHAR2 한글 왕복 손실");
            } finally {
                try (Statement s = c.createStatement()) { s.execute("DROP TABLE enc_gate_v"); }
            }
        }
    }

    @Test
    void clob_roundTripsKoreanLossless_candidate1() throws Exception {
        // §4.5 1순위 후보(String 트릭) 검증. 실패(=? 치환) 시 2순위(바이트 스트림)로 전환.
        try (Connection c = DriverManager.getConnection(JDBC_URL, USER, PASSWORD)) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE enc_gate_c (id NUMBER, val CLOB)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO enc_gate_c VALUES (1, ?)")) {
                ps.setString(1, EncodingConverter.toDb(KO.repeat(200)));  // 대용량 LOB 경로 유도
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT val FROM enc_gate_c WHERE id = 1");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals(KO.repeat(200), EncodingConverter.fromDb(rs.getString(1)), "CLOB 한글 왕복 손실");
            } finally {
                try (Statement s = c.createStatement()) { s.execute("DROP TABLE enc_gate_c"); }
            }
        }
    }
}
