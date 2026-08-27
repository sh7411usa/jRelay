package com.sh7411usa.jrelay.sms;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class PhoneNumberUtilsTest {

    private static final String EXPECTED = "+12345678910";

    @Test
    public void normalize_acceptsAllDocumentedUsFormats() {
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("+12345678910"));
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("+1 (234) 567-8910"));
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("1-234-567-8910"));
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("234-567-8910"));
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("2345678910"));
        assertEquals(EXPECTED, PhoneNumberUtils.normalize("(234) 567-8910"));
    }

    @Test
    public void normalize_rejectsGarbageInput() {
        assertNull(PhoneNumberUtils.normalize("12345"));
        assertNull(PhoneNumberUtils.normalize("not a number"));
        assertNull(PhoneNumberUtils.normalize(""));
    }

    @Test
    public void splitLeadingNumberAndRest_handlesHyphenatedFormats() {
        assertArrayEquals(
                new String[]{"234-567-8910", "John Doe"},
                PhoneNumberUtils.splitLeadingNumberAndRest("234-567-8910 John Doe"));
        assertArrayEquals(
                new String[]{"1-234-567-8910", "John Doe"},
                PhoneNumberUtils.splitLeadingNumberAndRest("1-234-567-8910 John Doe"));
    }

    @Test
    public void splitLeadingNumberAndRest_handlesSpaceInsideNumber() {
        assertArrayEquals(
                new String[]{"+1 (234) 567-8910", "John Doe"},
                PhoneNumberUtils.splitLeadingNumberAndRest("+1 (234) 567-8910 John Doe"));
    }

    @Test
    public void splitLeadingNumberAndRest_supportsAdminRenameSyntax() {
        assertArrayEquals(
                new String[]{"914-433-5742", "Rabbi New Name"},
                PhoneNumberUtils.splitLeadingNumberAndRest("914-433-5742 Rabbi New Name"));
    }

    @Test
    public void splitTrailingNumberAndRest_handlesNicknameFirst() {
        assertArrayEquals(
                new String[]{"234-567-8910", "User"},
                PhoneNumberUtils.splitTrailingNumberAndRest("User 234-567-8910"));
        assertArrayEquals(
                new String[]{"1-234-567-8910", "John Doe"},
                PhoneNumberUtils.splitTrailingNumberAndRest("John Doe 1-234-567-8910"));
    }

    @Test
    public void splitTrailingNumberAndRest_handlesSpaceInsideNumber() {
        assertArrayEquals(
                new String[]{"+1 (234) 567-8910", "John Doe"},
                PhoneNumberUtils.splitTrailingNumberAndRest("John Doe +1 (234) 567-8910"));
    }

    @Test
    public void splitNumberAndOptionalNickname_acceptsNumberOnly() {
        assertArrayEquals(
                new String[]{"234-567-8910", ""},
                PhoneNumberUtils.splitNumberAndOptionalNickname("234-567-8910"));
    }

    @Test
    public void defaultNickname_usesOnlyLastFourDigits() {
        assertEquals("Member 8910", PhoneNumberUtils.defaultNickname(EXPECTED));
    }
}
