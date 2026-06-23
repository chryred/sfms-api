package com.sfms.api.encoding;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * CLOB 한글 컬럼용 MyBatis TypeHandler. (DESIGN.md §4.5)
 *
 * ⚠️ CLOB은 VARCHAR2 트릭이 그대로 통한다고 보장되지 않는다. 게이트 ①-B로 반드시 검증할 것.
 *
 * 본 구현 = §4.5 "1순위 후보": getString/setString(드라이버가 크기 따라 LOB 경로 자동 전환)에
 * VARCHAR2와 동일한 toDb/fromDb를 적용. SFMS의 드라이버/실데이터에서 무손실이면 이 핸들러를 채택한다.
 *
 * 만약 게이트 ①-B에서 한글이 '?'로 소실되면 → "2순위 후보(원시 바이트 + MS949 직접 디코딩)"로 교체.
 * 2순위 골격은 클래스 하단 주석 참고. 어느 쪽이든 기존 운영 CLOB 실데이터로 왕복 검증 후 확정.
 */
@MappedTypes(String.class)
@MappedJdbcTypes(value = JdbcType.CLOB, includeNullJdbcType = true)
public class KoreanClobTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, EncodingConverter.toDb(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return EncodingConverter.fromDb(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return EncodingConverter.fromDb(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return EncodingConverter.fromDb(cs.getString(columnIndex));
    }

    /*
     * --- 2순위 후보 골격 (1순위가 손실 낼 때만) ---
     * 드라이버 charset 변환을 우회하기 위해 바이트로 읽고 MS949로 직접 디코딩한다.
     *
     *   읽기:  java.sql.Clob clob = rs.getClob(col);
     *          byte[] raw = clob.getAsciiStream().readAllBytes();  // 변환 없는 바이트 확보 목적
     *          return new String(raw, Charset.forName("MS949"));
     *   쓰기:  byte[] raw = value.getBytes(Charset.forName("MS949"));
     *          ps.setAsciiStream(i, new ByteArrayInputStream(raw), raw.length);
     *
     * 주의: getAsciiStream/setAsciiStream의 실제 바이트 통과 여부도 드라이버 의존 → 게이트 ①-B 필수.
     */
}
