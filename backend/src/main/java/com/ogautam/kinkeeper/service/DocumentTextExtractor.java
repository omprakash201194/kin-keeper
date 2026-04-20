package com.ogautam.kinkeeper.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Base64ImageSource;
import com.anthropic.models.messages.Base64PdfSource;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.DocumentBlockParam;
import com.anthropic.models.messages.ImageBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlockParam;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.List;

/**
 * Extracts plain text from a document via Claude vision so search_documents
 * can match the actual contents, not just filename/notes/tags. Runs on upload
 * using the uploader's own Claude API key (BYOK).
 *
 * Cheap-to-skip: if the mime type isn't inlineable (PDF / jpeg / png / gif /
 * webp) or the user has no key, we return null and the caller leaves the
 * document's extractedText blank.
 */
@Slf4j
@Service
public class DocumentTextExtractor {

    private static final String PROMPT = """
            Extract all readable text from this document. Output ONLY the text,
            verbatim, with minimal formatting — no commentary, no summary, no
            markdown, no bullet lists. Preserve line breaks where they carry
            meaning (line items, table rows, addresses). If the document has
            no text, output an empty string.
            """;

    private static final int MAX_TOKENS = 4096;
    private static final int MAX_TEXT_LEN = 40_000; // Firestore doc field soft limit is 1MB, be generous but bounded

    private final UserService userService;
    private final String defaultModel;

    public DocumentTextExtractor(UserService userService,
                                 @Value("${anthropic.default-model:claude-sonnet-4-6}") String defaultModel) {
        this.userService = userService;
        this.defaultModel = defaultModel;
    }

    /**
     * Returns the extracted text, or null if extraction was skipped/failed.
     * Never throws — this is a best-effort enrichment path; a failed extraction
     * must not block the upload itself.
     */
    public String tryExtract(String userUid, byte[] bytes, String mimeType, String fileName) {
        if (bytes == null || bytes.length == 0) return null;
        String apiKey;
        try {
            apiKey = userService.getApiKey(userUid);
        } catch (Exception e) {
            log.warn("extractedText: could not load API key for {}: {}", userUid, e.getMessage());
            return null;
        }
        if (apiKey == null) {
            log.info("extractedText: skipping '{}' — uploader has no Claude API key", fileName);
            return null;
        }

        ContentBlockParam fileBlock = buildFileBlock(bytes, mimeType, fileName);
        if (fileBlock == null) {
            log.info("extractedText: skipping '{}' — mime {} not inlineable", fileName, mimeType);
            return null;
        }

        try {
            AnthropicClient client = AnthropicOkHttpClient.builder().apiKey(apiKey).build();
            MessageCreateParams params = MessageCreateParams.builder()
                    .model(defaultModel)
                    .maxTokens(MAX_TOKENS)
                    .system(PROMPT)
                    .addMessage(MessageParam.builder()
                            .role(MessageParam.Role.USER)
                            .contentOfBlockParams(List.of(
                                    fileBlock,
                                    ContentBlockParam.ofText(TextBlockParam.builder()
                                            .text("Extract the text content. Return text only, no commentary.")
                                            .build())))
                            .build())
                    .build();

            Message response = client.messages().create(params);
            String text = collectText(response).trim();
            if (text.isEmpty()) return null;
            if (text.length() > MAX_TEXT_LEN) {
                log.info("extractedText: '{}' output {} chars, truncating to {}", fileName, text.length(), MAX_TEXT_LEN);
                text = text.substring(0, MAX_TEXT_LEN);
            }
            log.info("extractedText: extracted {} chars from '{}' ({})", text.length(), fileName, mimeType);
            return text;
        } catch (Exception e) {
            log.warn("extractedText: extraction failed for '{}' ({}): {}", fileName, mimeType, e.getMessage());
            return null;
        }
    }

    private static ContentBlockParam buildFileBlock(byte[] bytes, String mimeType, String fileName) {
        String mime = mimeType == null ? "" : mimeType.toLowerCase();
        String base64 = Base64.getEncoder().encodeToString(bytes);
        if ("application/pdf".equals(mime)) {
            return ContentBlockParam.ofDocument(DocumentBlockParam.builder()
                    .source(Base64PdfSource.builder()
                            .data(base64)
                            .build())
                    .title(fileName != null ? fileName : "document")
                    .build());
        }
        Base64ImageSource.MediaType imgType = switch (mime) {
            case "image/jpeg", "image/jpg" -> Base64ImageSource.MediaType.IMAGE_JPEG;
            case "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG;
            case "image/gif" -> Base64ImageSource.MediaType.IMAGE_GIF;
            case "image/webp" -> Base64ImageSource.MediaType.IMAGE_WEBP;
            default -> null;
        };
        if (imgType == null) return null;
        return ContentBlockParam.ofImage(ImageBlockParam.builder()
                .source(Base64ImageSource.builder()
                        .data(base64)
                        .mediaType(imgType)
                        .build())
                .build());
    }

    private static String collectText(Message response) {
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : response.content()) {
            block.text().ifPresent(tb -> {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tb.text());
            });
        }
        return sb.toString();
    }
}
