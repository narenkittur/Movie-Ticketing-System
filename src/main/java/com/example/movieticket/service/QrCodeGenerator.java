package com.example.movieticket.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

/**
 * Module 7 (plan/qrtickets.md section 4.2) - the only class in the project that
 * imports {@code com.google.zxing}. One public method, {@link #toPng}, a pure
 * function of a value the caller already has: encode {@code text} as a QR code,
 * render it to a PNG, return the bytes. Nothing here is ever persisted -
 * TicketService generates a fresh PNG on every request, which takes well under
 * a millisecond at these sizes.
 *
 * <p>{@code ERROR_CORRECTION = M} rather than ZXing's default L - survives a
 * creased printout or a fingerprint on a phone screen, still well within spec.
 * {@code MARGIN = 2} rather than ZXing's default quiet zone of 4 modules, which
 * wastes noticeable space at small sizes.
 */
@Component
public class QrCodeGenerator {

    private static final Map<EncodeHintType, Object> HINTS = Map.of(
            EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN, 2);

    /**
     * @param text   the exact string to encode - Module 7 always passes the bare
     *               {@code bookingReference} UUID, never a URL or a signed payload
     *               (plan/qrtickets.md section 4.1)
     * @param sizePx square image side length in pixels
     * @return PNG-encoded bytes, ready to stream straight to the response body
     */
    public byte[] toPng(String text, int sizePx) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, HINTS);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (WriterException | IOException e) {
            // Should be unreachable for a bounded-size UUID string - a real failure
            // here means a broken ZXing install, not bad user input, so this isn't
            // one of GlobalExceptionHandler's mapped exception types.
            throw new IllegalStateException("Failed to generate QR code for a " + sizePx + "px image", e);
        }
    }
}
