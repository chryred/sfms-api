package com.sfms.api.encoding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 게이트 ①의 1단계: DB 없이 toDb/fromDb 대칭성(왕복 무손실)을 검증한다.
 *
 * ⚠️ 범위 주의: 이 테스트는 변환기 자체의 대칭성만 보장한다.
 *   실제 드라이버/DB 왕복(0x80-0xFF가 US7ASCII를 무손실 통과하는지)은
 *   {@code EncodingRoundTripIT}(통합 게이트, DESIGN §4 / §4.5)로 별도 검증해야 한다.
 */
class EncodingConverterTest {

    @Test
    void roundTrip_preservesKoreanAndMixed() {
        // 주의: 모두 MS949(CP949) 인코딩이 확실한 문자만 사용한다.
        // 중점(·)·엠대시(—)처럼 CP949 매핑이 미묘한 문자는 통합 게이트(IT)에서 실데이터로 확인.
        String[] samples = {
            "한글", "시설물 관리 시스템", "가나다라마바사아자차카타파하",
            "ABC123", "한글ABC혼합123", "특수문자 ~!@#$%^&*()_+-=[]{}",
            "줄바꿈\n탭\t포함", "엘리베이터, 소방설비 점검 이력", ""
        };
        for (String s : samples) {
            assertEquals(s, EncodingConverter.fromDb(EncodingConverter.toDb(s)),
                "round-trip 실패: " + s);
        }
    }

    @Test
    void nullSafe() {
        assertNull(EncodingConverter.toDb(null));
        assertNull(EncodingConverter.fromDb(null));
    }

    @Test
    void storedForm_isLatin1Representable() {
        // toDb 결과의 모든 char는 0x00-0xFF여야 한다 (ISO-8859-1 표현 가능 = DB 저장 가능 전제).
        String stored = EncodingConverter.toDb("시설물 점검");
        for (char c : stored.toCharArray()) {
            assertTrue(c <= 0xFF, "Latin-1 범위 초과 char(0x" + Integer.toHexString(c) + ")");
        }
    }
}
