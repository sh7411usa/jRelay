package com.sh7411usa.jrelay.sms.mms;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** Hand-rolled m-send-req PDU (WAP-209/WAP-230): one text/plain part, one "To" header per recipient. */
public final class MmsPduWriter {
    private MmsPduWriter() {
    }

    public static byte[] buildSendReq(List<String> recipientsE164, String textBody, String transactionId) {
        requireNonEmpty(recipientsE164 == null || recipientsE164.isEmpty(), "recipientsE164");
        requireNonEmpty(textBody == null || textBody.isEmpty(), "textBody");
        requireNonEmpty(transactionId == null || transactionId.isEmpty(), "transactionId");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(0x8C); // X-Mms-Message-Type
            out.write(0x80); // m-send-req
            out.write(0x98); // X-Mms-Transaction-Id
            writeTextString(out, transactionId);
            out.write(0x8D); // X-Mms-MMS-Version
            out.write(0x92); // 1.2
            out.write(0x89); // From: value-length 1, insert-address-token (OS fills in own number)
            out.write(0x01);
            out.write(0x81);
            // Repeated To headers make this ONE multi-recipient group MMS, not N separate ones.
            for (String recipient : recipientsE164) {
                out.write(0x97);
                writeTextString(out, normalizeRecipient(recipient));
            }
            out.write(0x84); // Content-Type: MUST be the last header
            out.write(0xA3); // application/vnd.wap.multipart.mixed
            byte[] bodyBytes = textBody.getBytes(StandardCharsets.UTF_8);
            // value-length 3, text/plain, charset param, UTF-8 (MIBenum 106)
            byte[] partHeaders = {0x03, (byte) 0x83, (byte) 0x81, (byte) 0xEA};
            writeUintvar(out, 1); // part count
            writeUintvar(out, partHeaders.length);
            writeUintvar(out, bodyBytes.length);
            out.write(partHeaders);
            out.write(bodyBytes);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private static void requireNonEmpty(boolean empty, String name) {
        if (empty) throw new IllegalArgumentException(name + " must not be null/empty");
    }

    private static String normalizeRecipient(String recipient) {
        requireNonEmpty(recipient == null || recipient.isEmpty(), "recipient");
        return recipient.toUpperCase(Locale.US).contains("/TYPE=PLMN") ? recipient : recipient + "/TYPE=PLMN";
    }

    /** NUL-terminated text-string; first byte >= 0x80 needs the 0x7F Quote prefix (WAP-230 5.2.9). */
    private static void writeTextString(ByteArrayOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 0 && (bytes[0] & 0xFF) >= 0x80) out.write(0x7F);
        out.write(bytes);
        out.write(0x00);
    }

    /** WSP uintvar: 7 bits per byte, MSB first, continuation bit on all but the last byte. */
    private static void writeUintvar(ByteArrayOutputStream out, long value) {
        if (value < 0) throw new IllegalArgumentException("uintvar value must be non-negative: " + value);
        int[] groups = new int[10];
        int count = 0;
        do {
            groups[count++] = (int) (value & 0x7F);
            value >>>= 7;
        } while (value != 0);
        for (int i = count - 1; i >= 0; i--) out.write(i != 0 ? groups[i] | 0x80 : groups[i]);
    }
}
