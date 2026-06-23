package com.sfms.api.encoding;

import java.nio.charset.Charset;

/**
 * US7ASCII Oracle 레거시 더블 인코딩 변환기. (DESIGN.md §3)
 *
 * <pre>
 *   저장: 유니코드 String -> (MS949 바이트) -> ISO-8859-1로 재해석한 String
 *   조회: DB의 Latin-1 String -> (ISO-8859-1 바이트) -> MS949로 디코딩한 정상 String
 * </pre>
 *
 * 원칙:
 *  - Charset은 항상 명시한다. 절대 file.encoding/기본 Charset에 의존하지 않는다.
 *  - 이 변환은 DB 영속화 경계(TypeHandler)에서만 호출한다. Controller/Service/JSON은 정상 유니코드.
 */
public final class EncodingConverter {

    private static final Charset MS949 = Charset.forName("MS949");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private EncodingConverter() {}

    /** 저장 시: 유니코드 -> DB 저장형(Latin-1 재해석). */
    public static String toDb(String s) {
        if (s == null) return null;
        return new String(s.getBytes(MS949), LATIN1);
    }

    /** 조회 시: DB 저장형(Latin-1 재해석) -> 정상 유니코드. */
    public static String fromDb(String s) {
        if (s == null) return null;
        return new String(s.getBytes(LATIN1), MS949);
    }
}
