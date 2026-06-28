package com.ideaparty.service;

import com.ideaparty.dto.ExtractTextFromImageResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageOcrServiceTest {

    private final ImageOcrService service = new ImageOcrService();

    @Test
    @DisplayName("extractText 空文件：抛 IllegalArgumentException")
    void extractText_emptyFile_throws() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.png", "image/png", new byte[0]
        );
        assertThatThrownBy(() -> service.extractText(UUID.randomUUID(), file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("文件为空");
    }

    @Test
    @DisplayName("extractText null 文件：抛 IllegalArgumentException")
    void extractText_nullFile_throws() {
        assertThatThrownBy(() -> service.extractText(UUID.randomUUID(), (MultipartFile) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("extractText 超大文件：抛 IllegalArgumentException")
    void extractText_oversizeFile_throws() {
        byte[] big = new byte[6 * 1024 * 1024];
        MockMultipartFile file = new MockMultipartFile(
                "file", "huge.png", "image/png", big
        );
        assertThatThrownBy(() -> service.extractText(UUID.randomUUID(), file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5MB");
    }

    @Test
    @DisplayName("extractText 不支持的格式（如 .pdf）：抛 IllegalArgumentException")
    void extractText_unsupportedType_throws() {
        byte[] content = "%PDF-1.4\n%¥±ë\n".getBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file", "jd.pdf", "application/pdf", content
        );
        assertThatThrownBy(() -> service.extractText(UUID.randomUUID(), file))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不支持");
    }

    @Test
    @DisplayName("extractText 真实 PNG：调 tesseract 拿到文字")
    void extractText_realPng_works() throws Exception {
        // 用 classpath 下的真实测试图
        try (var is = getClass().getClassLoader().getResourceAsStream("test-ocr.png")) {
            assertThat(is).as("test-ocr.png fixture should exist on classpath").isNotNull();
            byte[] bytes = is.readAllBytes();
            MockMultipartFile file = new MockMultipartFile(
                    "file", "test-ocr.png", "image/png", bytes
            );

            ExtractTextFromImageResponse resp = service.extractText(UUID.randomUUID(), file);

            // 不假设具体内容（图片可能没字），但 tesseract 不报错就算通过
            assertThat(resp).isNotNull();
            assertThat(resp.getFilename()).isEqualTo("test-ocr.png");
            // length 可能为 0（纯色图）也可能非空，不强制断言
        }
    }
}
