package com.skyanchor.bookkeeping.ai;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

/**
 * OCR 文本清洗（V5）：把识别出的原始行整理成解析器友好的文本。
 *
 * <p>规则刻意保守：只去掉明显无信息量的行（空行、纯符号、超长乱码），
 * **绝不动金额行与日期行**——多金额小票的「实付判断」交给解析层与用户确认，
 * 清洗层一旦删错行就再也找不回来。
 */
public final class OcrTextCleaner {

    private OcrTextCleaner() {
    }

    /**
     * 清洗文本行并按原顺序拼接（换行分隔）。
     *
     * @param lines OCR 识别行
     * @return 拼接后的文本；全部行都被过滤时返回空串
     */
    @NonNull
    public static String clean(@NonNull List<OcrService.OcrLine> lines) {
        List<String> kept = new ArrayList<>();
        for (OcrService.OcrLine line : lines) {
            String text = line.text == null ? "" : line.text.trim();
            if (keep(text)) {
                kept.add(text);
            }
        }
        return String.join("\n", kept);
    }

    static boolean keep(@NonNull String text) {
        if (text.isEmpty()) {
            return false;
        }
        // 只剩符号 / 单个字符的行没有信息量（如「|」「·」「---」）。
        int useful = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c >= 0x4E00 && c <= 0x9FFF) {
                useful++;
            }
        }
        return useful >= 2;
    }
}
