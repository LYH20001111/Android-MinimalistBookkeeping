package com.skyanchor.bookkeeping.ai;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;
import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 云端 LLM 解析器（V5）：调用 OpenAI 兼容的 {@code /chat/completions} 接口，
 * 把一句话描述 / OCR 文本解析成账单草稿。
 *
 * <p>与具体厂商解耦（基线第 49 章）：地址、密钥、模型名都来自
 * {@link AiConfigStore}，业务代码不出现任何厂商 SDK。输出强制 JSON
 * （基线第 26 章），不合法时按解析失败处理并降级到本地规则（由
 * {@link AiRepository} 负责）。
 */
public class CloudAiTransactionParser implements AiTransactionParser {

    private static final MediaType JSON_MEDIA_TYPE =
            MediaType.parse("application/json; charset=utf-8");

    private static final String SYSTEM_PROMPT =
            "你是记账助手。把用户给出的消费/收入描述解析成账单 JSON，"
                    + "只输出一个 JSON 对象，不要输出任何解释、前缀或代码块标记。\n"
                    + "字段定义：\n"
                    + "- type: \"expense\"（支出）或 \"income\"（收入）\n"
                    + "- amount: 金额，整数，单位为分（32 元输出 3200）；用户没有给出明确金额时输出 null\n"
                    + "- date: 日期 \"yyyy-MM-dd\"；用户没有明确说明时输出 null（不要猜）\n"
                    + "- time: 时间 \"HH:mm\"；未提到时输出 null\n"
                    + "- merchant: 商家名称；未提到时输出 null\n"
                    + "- category: 从候选分类里选最匹配的分类名；都不合适时输出 null\n"
                    + "- note: 一句话备注，精简概括这笔消费\n"
                    + "- confidence: 0 到 1000 的整数，表示整体置信度\n"
                    + "规则：绝不要编造或推算用户未给出的精确金额；"
                    + "「一百多」「几十块」这类模糊表述一律输出 null。";

    private final AiConfigStore configStore;
    private final OkHttpClient client;
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public CloudAiTransactionParser(@NonNull AiConfigStore configStore) {
        this.configStore = configStore;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public void parse(@NonNull String text,
                      @NonNull List<CategoryEntity> categories,
                      @NonNull Callback<TransactionDraft> callback) {
        if (!configStore.isCloudConfigured()) {
            mainHandler.post(() -> callback.onError(
                    new AiException(AiException.Kind.NOT_CONFIGURED, "云端 AI 未配置")));
            return;
        }
        executor.execute(() -> {
            try {
                TransactionDraft draft = requestAndParse(text, categories);
                mainHandler.post(() -> callback.onResult(draft));
            } catch (AiException e) {
                mainHandler.post(() -> callback.onError(e));
            } catch (IOException e) {
                mainHandler.post(() -> callback.onError(
                        new AiException(AiException.Kind.NETWORK, "云端 AI 请求失败", e)));
            } catch (RuntimeException e) {
                mainHandler.post(() -> callback.onError(
                        new AiException(AiException.Kind.PARSE_FAILED, "云端 AI 返回异常", e)));
            }
        });
    }

    private TransactionDraft requestAndParse(@NonNull String text,
                                             @NonNull List<CategoryEntity> categories)
            throws IOException {
        String content = request(text, categories);
        ParsedBill bill = extractJson(content);
        if (bill == null) {
            throw new AiException(AiException.Kind.PARSE_FAILED, "云端 AI 未返回合法 JSON");
        }
        return toDraft(bill, categories);
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private String request(@NonNull String text, @NonNull List<CategoryEntity> categories)
            throws IOException {
        String baseUrl = configStore.getBaseUrl();
        String url = buildUrl(baseUrl);
        ChatRequest request = new ChatRequest();
        request.model = configStore.getModel();
        request.temperature = 0.1;
        request.messages.add(new Message("system", SYSTEM_PROMPT));
        request.messages.add(new Message("user", buildUserPrompt(text, categories)));

        Response response = null;
        try {
            response = client.newCall(new Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + configStore.getApiKey())
                    .post(RequestBody.create(gson.toJson(request), JSON_MEDIA_TYPE))
                    .build()).execute();
            if (!response.isSuccessful()) {
                throw new AiException(AiException.Kind.NETWORK,
                        "云端 AI 服务返回 " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new AiException(AiException.Kind.NETWORK, "云端 AI 响应为空");
            }
            ChatResponse chatResponse = gson.fromJson(body.string(), ChatResponse.class);
            if (chatResponse == null || chatResponse.choices == null
                    || chatResponse.choices.isEmpty()
                    || chatResponse.choices.get(0).message == null) {
                throw new AiException(AiException.Kind.PARSE_FAILED, "云端 AI 未返回内容");
            }
            return chatResponse.choices.get(0).message.content;
        } finally {
            if (response != null) {
                response.close();
            }
        }
    }

    /** 拼接 chat/completions 地址，容忍 base URL 带或不带尾部斜杠与 /v1。 */
    @NonNull
    static String buildUrl(@NonNull String baseUrl) {
        String trimmed = baseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed;
        }
        return trimmed + "/chat/completions";
    }

    @NonNull
    static String buildUserPrompt(@NonNull String text,
                                  @NonNull List<CategoryEntity> categories) {
        StringBuilder sb = new StringBuilder();
        long now = System.currentTimeMillis();
        sb.append("今天是 ").append(DraftValidator.formatDate(now))
                .append(' ').append(DateUtil.formatHourMinuteOf(now)).append("。\n");
        sb.append("候选分类：\n支出: ");
        StringBuilder expense = new StringBuilder();
        StringBuilder income = new StringBuilder();
        for (CategoryEntity category : categories) {
            if (category.type == CategoryEntity.TYPE_EXPENSE) {
                expense.append(category.name).append("、");
            } else if (category.type == CategoryEntity.TYPE_INCOME) {
                income.append(category.name).append("、");
            }
        }
        sb.append(expense.length() > 0 ? expense.substring(0, expense.length() - 1) : "无");
        sb.append("\n收入: ");
        sb.append(income.length() > 0 ? income.substring(0, income.length() - 1) : "无");
        sb.append("\n用户输入：").append(text);
        return sb.toString();
    }

    /** 剥掉 markdown 代码围栏后截取首尾大括号之间的 JSON。 */
    @Nullable
    static ParsedBill extractJson(@NonNull String content) {
        String trimmed = content.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return new Gson().fromJson(trimmed.substring(start, end + 1), ParsedBill.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // JSON → Draft
    // ------------------------------------------------------------------

    private TransactionDraft toDraft(@NonNull ParsedBill bill,
                                     @NonNull List<CategoryEntity> categories) {
        TransactionDraft draft = new TransactionDraft();
        draft.source = TransactionDraft.SOURCE_AI;
        draft.type = "income".equalsIgnoreCase(bill.type)
                ? CategoryEntity.TYPE_INCOME : CategoryEntity.TYPE_EXPENSE;
        if (bill.amount != null && bill.amount > 0L && bill.amount <= 9_999_999_999L) {
            draft.amount = bill.amount;
        }
        draft.date = parseDate(bill.date);
        draft.time = bill.time;
        draft.merchant = emptyToNull(bill.merchant);
        draft.note = emptyToNull(bill.note);
        int confidence = DraftValidator.clamp(
                bill.confidence == null ? TransactionDraft.CONFIDENCE_LOW : bill.confidence);
        draft.confidence = confidence;
        draft.amountConfidence = draft.amount > 0L
                ? Math.max(confidence, TransactionDraft.CONFIDENCE_MEDIUM)
                : TransactionDraft.CONFIDENCE_LOW;

        CategoryEntity category = findCategory(categories, draft.type, bill.category);
        if (category != null) {
            draft.categoryId = category.id;
            draft.categoryConfidence = Math.max(confidence, TransactionDraft.CONFIDENCE_MEDIUM);
        } else {
            // 模型给的名字对不上候选列表时不猜，交给确认页落到默认分类。
            draft.categoryConfidence = TransactionDraft.CONFIDENCE_LOW;
        }
        DraftValidator.normalize(draft, System.currentTimeMillis());
        return draft;
    }

    /** 分类名对齐：先精确/包含匹配，再退到关键词词典推断。 */
    @Nullable
    private static CategoryEntity findCategory(@NonNull List<CategoryEntity> categories,
                                               int type, @Nullable String name) {
        CategoryEntity matched = LocalRuleTransactionParser.findByName(categories, type, name);
        if (matched != null) {
            return matched;
        }
        return LocalRuleTransactionParser.guessByKeyword(
                name == null ? "" : name, categories, type);
    }

    private static long parseDate(@Nullable String date) {
        if (date == null || date.length() != 10) {
            return 0L;
        }
        try {
            return DateUtil.dayMillisOf(Integer.parseInt(date.substring(0, 4)),
                    Integer.parseInt(date.substring(5, 7)),
                    Integer.parseInt(date.substring(8, 10)));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    @Nullable
    private static String emptyToNull(@Nullable String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }

    // ------------------------------------------------------------------
    // Gson DTO（与 ApiDtos 同风格：public 字段、camelCase）
    // ------------------------------------------------------------------

    static final class ChatRequest {
        String model;
        double temperature;
        List<Message> messages = new java.util.ArrayList<>();
    }

    static final class Message {
        String role;
        String content;

        Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    static final class ChatResponse {
        List<Choice> choices;
    }

    static final class Choice {
        Message message;
    }

    /** 云端返回的账单 JSON。 */
    static final class ParsedBill {
        String type;
        Long amount;
        String date;
        String time;
        String merchant;
        String category;
        String note;
        Integer confidence;
    }
}
