package com.sfms.api.encoding;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * VARCHAR2 한글 컬럼용 MyBatis TypeHandler. (DESIGN.md §3.2)
 * 한글이 들어가는 VARCHAR2 컬럼에만 명시적으로 매핑한다.
 */
@MappedTypes(String.class)
public class KoreanStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType)
            throws SQLException {
        ps.setString(i, EncodingConverter.toDb(parameter));   // 바인딩 시 인코딩
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return EncodingConverter.fromDb(rs.getString(columnName));   // 조회 시 디코딩
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return EncodingConverter.fromDb(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return EncodingConverter.fromDb(cs.getString(columnIndex));
    }
}
