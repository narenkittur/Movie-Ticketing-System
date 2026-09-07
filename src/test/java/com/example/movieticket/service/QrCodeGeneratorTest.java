package com.example.movieticket.service;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Module 7 (plan/qrtickets.md section 8, test 1) - the rare test in this project
 * with no mock and no ambiguity: generate a PNG for a known string, decode it
 * back with ZXing's own reader stack, assert round-trip equality. Catches a
 * misconfigured hint or encoding bug that eyeballing a black-and-white square
 * never would.
 */
class QrCodeGeneratorTest {

    private final QrCodeGenerator generator = new QrCodeGenerator();

    @Test
    void toPng_roundTrip_decodesBackToTheSameText() throws Exception {
        String text = "9f2c1e4a-7b03-4d51-a0f2-6e8c5d1b93aa";

        byte[] png = generator.toPng(text, 320);
        assertTrue(png.length > 0);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(image)));
        Result result = new MultiFormatReader().decode(bitmap);

        assertEquals(text, result.getText());
    }

    @Test
    void toPng_respectsRequestedSize() throws Exception {
        byte[] png = generator.toPng("some-reference", 512);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));

        assertEquals(512, image.getWidth());
        assertEquals(512, image.getHeight());
    }
}
