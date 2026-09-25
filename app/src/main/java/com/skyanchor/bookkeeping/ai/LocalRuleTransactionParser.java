package com.skyanchor.bookkeeping.ai;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.skyanchor.bookkeeping.data.entity.CategoryEntity;
import com.skyanchor.bookkeeping.util.AmountUtil;
import com.skyanchor.bookkeeping.util.Callback;
import com.skyanchor.bookkeeping.util.DateUtil;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线规则解析器（V5）：纯正则 + 关键词词典，把一句话描述或 OCR 文本解析成账单草稿。
 *
 * <p>定位是「兜底」而不是「智能」：不依赖网络与任何 AI 服务，保证断网可用
 * （基线第 34 章），同时也是云端解析失败 / 未配置时的降级路径。
 *
 * <p>核心原则（基线第 17、43 章）：识别不了就留空交给确认页，绝不臆造——
 * 「花了一百多」不产生金额，「昨天吃饭」不产生分类之外的任何猜测。
 *
 * <p>线程约定：解析本身在调用线程同步执行（纯 CPU，耗时毫秒级），
 * 回调统一切回主线程。
 */
public class LocalRuleTransactionParser implements AiTransactionParser {

    /** 收入判定关键词：命中即按收入处理，其余一律支出。 */
    private static final Pattern INCOME_KEYWORDS = Pattern.compile(
            "到账|入账|收入|工资|薪资|薪水|奖金|分红|利息|红包|报销|进账|收了|收到|挣|赚|退款");

    /** 金额紧跟消费动词：「花了32」「到账8500」，置信度最高。 */
    private static final Pattern AMOUNT_AFTER_VERB = Pattern.compile(
            "(?:花了|花费了?|消费了?|支付了?|付了款|付了|付款|支出了?|到账|入账|收了|进账|发了)"
                    + "\\s*[¥￥]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)");

    /** 数字带货币单位：「6块」「128元」「35.5元」。 */
    private static final Pattern AMOUNT_WITH_UNIT = Pattern.compile(
            "([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*(?:元|块钱|块|RMB|CNY)");

    /** 小票里的实付标签：「实付 42.00」「合计：128」。 */
    private static final Pattern AMOUNT_LABELED = Pattern.compile(
            "(?:实付|实收|合计|总计|总额|总价|应付|应收|paid|total|amount)"
                    + "[^0-9\\n]{0,6}[¥￥]?\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    /** 小票里单独成行的金额行：「42.00」。 */
    private static final Pattern AMOUNT_STANDALONE_LINE = Pattern.compile(
            "^[ \\t]*[¥￥]?([0-9][0-9,]*\\.[0-9]{2})[ \\t]*$", Pattern.MULTILINE);

    /**
     * 兜底的裸数字（如「买耳机599」）。前后向断言排除日期（2026/09/25、9月25）、
     * 时间（12:36）等非金额上下文。
     */
    private static final Pattern AMOUNT_BARE = Pattern.compile(
            "(?<![0-9.:\\-/¥￥年月日])([0-9]{1,7}(?:\\.[0-9]{1,2})?)"
                    + "(?![0-9.%]|[月日号年点时:：/])");

    private static final Pattern DATE_ISO = Pattern.compile(
            "([0-9]{4})[-/.年]([0-9]{1,2})[-/.月]([0-9]{1,2})[日号]?");

    private static final Pattern DATE_MONTH_DAY = Pattern.compile(
            "([0-9]{1,2})月([0-9]{1,2})[日号]");

    private static final Pattern TIME_COLON = Pattern.compile("([0-9]{1,2}):([0-9]{2})");

    /** 「12点30」「晚上8点」「下午4点半」。 */
    private static final Pattern TIME_OCLOCK = Pattern.compile(
            "(上午|早上|中午|下午|傍晚|晚上|夜里)?\\s*([0-9]{1,2})[点时]((?:[0-9]{1,2})分?)?");

    /** 用户明确指定分类：「算交通」「记在餐饮」「归到教育」「分类是购物」。 */
    private static final Pattern CATEGORY_EXPLICIT = Pattern.compile(
            "(?:算|记在|记到|归到|归为|归在|分类是|分类为|属于)\\s*([一-龥]{1,8})");

    /** 商家描述：「在麦当劳吃了午饭」「在星巴克买咖啡」。 */
    private static final Pattern MERCHANT_AFTER_PREP = Pattern.compile(
            "在\\s*([一-龥A-Za-z0-9]{2,16}?)(?:花了|买了|吃了|喝了|点了|消费|购物|打车|充值)");

    /** 一行里含汉字即视为「内容行」（区别于纯数字的金额行、纯数字的日期行）。 */
    private static final Pattern HAS_CJK = Pattern.compile("[一-龥]");

    private final LongSupplier nowSupplier;
    /** 惰性创建：parseSync 在 JVM 单测里运行，不能在构造器触碰 android.os.Handler。 */
    @Nullable
    private volatile Handler mainHandler;

    public LocalRuleTransactionParser() {
        this(System::currentTimeMillis);
    }

    /** 测试注入时钟用。 */
    public LocalRuleTransactionParser(@NonNull LongSupplier nowSupplier) {
        this.nowSupplier = nowSupplier;
    }

    private Handler handler() {
        Handler handler = mainHandler;
        if (handler == null) {
            handler = new Handler(Looper.getMainLooper());
            mainHandler = handler;
        }
        return handler;
    }

    @Override
    public void parse(@NonNull String text,
                      @NonNull List<CategoryEntity> categories,
                      @NonNull Callback<TransactionDraft> callback) {
        try {
            TransactionDraft draft = parseSync(text, categories);
            handler().post(() -> callback.onResult(draft));
        } catch (AiException e) {
            handler().post(() -> callback.onError(e));
        } catch (RuntimeException e) {
            handler().post(() -> callback.onError(
                    new AiException(AiException.Kind.PARSE_FAILED, "规则解析异常", e)));
        }
    }

    /**
     * 同步解析入口，供单元测试直接断言。
     *
     * @throws AiException 文本为空时（PARSE_FAILED）
     */
    @NonNull
    public TransactionDraft parseSync(@NonNull String rawText,
                                      @NonNull List<CategoryEntity> categories) {
        String text = normalize(rawText);
        if (text.isEmpty()) {
            throw new AiException(AiException.Kind.PARSE_FAILED, "没有可解析的内容");
        }
        long now = nowSupplier.getAsLong();
        TransactionDraft draft = new TransactionDraft();
        draft.source = TransactionDraft.SOURCE_AI;

        boolean receipt = looksLikeReceipt(text);
        draft.type = detectType(text);
        draft.amount = detectAmount(text, receipt);
        draft.amountConfidence = draft.amount > 0L
                ? detectAmountConfidence(text, receipt)
                : TransactionDraft.CONFIDENCE_LOW;
        draft.date = detectDate(text, now);
        draft.time = detectTime(text, now);
        String receiptNote = null;
        if (receipt) {
            receiptNote = applyReceiptLines(text, draft);
        } else {
            draft.merchant = detectMerchant(text);
        }
        detectCategory(text, categories, draft);
        draft.note = buildNote(text, receiptNote);
        // 整体置信度取关键字段里较低的一档，确认页据此决定顶部文案。
        draft.confidence = Math.min(draft.amountConfidence, draft.categoryConfidence);
        DraftValidator.normalize(draft, now);
        return draft;
    }

    // ------------------------------------------------------------------
    // 预处理与整体判断
    // ------------------------------------------------------------------

    /** 全角数字 / 标点转半角，让后续正则只面对一种形态。 */
    @NonNull
    static String normalize(@NonNull String raw) {
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            if (c >= '０' && c <= '９') {
                sb.append((char) (c - '０' + '0'));
            } else if (c == '．') {
                sb.append('.');
            } else if (c == '：') {
                sb.append(':');
            } else if (c == '，') {
                sb.append(',');
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    /** 是否为小票类文本：含 ISO 日期 / 独立金额行 / 三行以上结构。 */
    static boolean looksLikeReceipt(@NonNull String text) {
        if (text.indexOf('\n') < 0) {
            return false;
        }
        return DATE_ISO.matcher(text).find()
                || AMOUNT_STANDALONE_LINE.matcher(text).find()
                || text.split("\n").length >= 3;
    }

    // ------------------------------------------------------------------
    // 类型 / 金额 / 日期 / 时间
    // ------------------------------------------------------------------

    static int detectType(@NonNull String text) {
        return INCOME_KEYWORDS.matcher(text).find()
                ? CategoryEntity.TYPE_INCOME : CategoryEntity.TYPE_EXPENSE;
    }

    /** 按置信度从高到低依次尝试各种金额模式，返回「分」；识别不到返回 0。 */
    static long detectAmount(@NonNull String text, boolean receipt) {
        long cents = tryPattern(AMOUNT_AFTER_VERB, text);
        if (cents > 0L) {
            return cents;
        }
        cents = tryPattern(AMOUNT_WITH_UNIT, text);
        if (cents > 0L) {
            return cents;
        }
        cents = tryPattern(AMOUNT_LABELED, text);
        if (cents > 0L) {
            return cents;
        }
        if (receipt) {
            cents = tryPattern(AMOUNT_STANDALONE_LINE, text);
            if (cents > 0L) {
                return cents;
            }
        }
        return tryPattern(AMOUNT_BARE, text);
    }

    /** 与 {@link #detectAmount} 相同的优先级，返回实际命中模式的置信度档位。 */
    static int detectAmountConfidence(@NonNull String text, boolean receipt) {
        if (tryPattern(AMOUNT_AFTER_VERB, text) > 0L
                || tryPattern(AMOUNT_WITH_UNIT, text) > 0L
                || tryPattern(AMOUNT_LABELED, text) > 0L) {
            return TransactionDraft.CONFIDENCE_HIGH;
        }
        // 独立金额行 / 裸数字都算「有依据但不完全确定」，提示用户确认。
        return TransactionDraft.CONFIDENCE_MEDIUM;
    }

    private static long tryPattern(@NonNull Pattern pattern, @NonNull String text) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String group = matcher.group(1);
            if (group == null) {
                continue;
            }
            long cents = AmountUtil.parseToCents(group.replace(",", ""));
            if (cents > 0L) {
                return cents;
            }
        }
        return 0L;
    }

    /** 依次尝试 ISO 日期、相对词、月日；都没有则默认今天（基线第 18 章）。 */
    static long detectDate(@NonNull String text, long now) {
        long today = DateUtil.startOfDay(now);
        Matcher matcher = DATE_ISO.matcher(text);
        if (matcher.find()) {
            long parsed = safeDayMillis(matcher.group(1), matcher.group(2), matcher.group(3));
            if (parsed > 0L) {
                return parsed;
            }
        }
        if (text.contains("前天")) {
            return DateUtil.addDays(today, -2);
        }
        if (text.contains("昨天") || text.contains("昨日") || text.contains("昨晚")) {
            return DateUtil.addDays(today, -1);
        }
        if (text.contains("今天") || text.contains("今日") || text.contains("今晚")) {
            return today;
        }
        matcher = DATE_MONTH_DAY.matcher(text);
        if (matcher.find()) {
            // 只写「月日」默认当年；落在未来（超过明天）按去年理解，常见于跨年小票。
            long parsed = safeDayMillis(String.valueOf(DateUtil.yearOf(now)),
                    matcher.group(1), matcher.group(2));
            if (parsed > 0L) {
                if (parsed > DateUtil.addDays(today, 1)) {
                    parsed = DateUtil.addDays(parsed, -365);
                }
                return parsed;
            }
        }
        return today;
    }

    private static long safeDayMillis(@Nullable String year, @Nullable String month,
                                      @Nullable String day) {
        try {
            int y = Integer.parseInt(requireGroup(year));
            int m = Integer.parseInt(requireGroup(month));
            int d = Integer.parseInt(requireGroup(day));
            if (m < 1 || m > 12 || d < 1 || d > 31) {
                return 0L;
            }
            return DateUtil.dayMillisOf(y, m, d);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private static String requireGroup(@Nullable String value) {
        if (value == null) {
            throw new NumberFormatException("null group");
        }
        return value;
    }

    /** 「12:36」「晚上8点」；都没有则默认当前时间（基线第 18 章）。 */
    @NonNull
    static String detectTime(@NonNull String text, long now) {
        Matcher matcher = TIME_COLON.matcher(text);
        while (matcher.find()) {
            int hour = parseIntSafe(matcher.group(1));
            int minute = parseIntSafe(matcher.group(2));
            if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                return DateUtil.formatHourMinute(hour, minute);
            }
        }
        matcher = TIME_OCLOCK.matcher(text);
        while (matcher.find()) {
            int hour = parseIntSafe(matcher.group(2));
            int minute = matcher.group(3) == null ? 0
                    : parseIntSafe(matcher.group(3).replace("分", ""));
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                continue;
            }
            String period = matcher.group(1);
            if (("下午".equals(period) || "傍晚".equals(period) || "晚上".equals(period)
                    || "夜里".equals(period)) && hour < 12) {
                hour += 12;
            } else if ("中午".equals(period) && hour < 11) {
                hour += 12;
            }
            return DateUtil.formatHourMinute(hour, minute);
        }
        return DateUtil.formatHourMinuteOf(now);
    }

    private static int parseIntSafe(@Nullable String value) {
        try {
            return value == null ? -1 : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // 商家 / 分类 / 备注
    // ------------------------------------------------------------------

    @Nullable
    private static String detectMerchant(@NonNull String text) {
        Matcher matcher = MERCHANT_AFTER_PREP.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    /**
     * 小票文本的行级抽取：首个短内容行当商家，其余内容行拼接为描述文本（返回）。
     * 与 {@link #looksLikeReceipt} 配套，覆盖
     * 「麦当劳 / 2026-09-25 12:36 / 午餐套餐 / 42.00」这类结构。
     */
    @Nullable
    private static String applyReceiptLines(@NonNull String text,
                                            @NonNull TransactionDraft draft) {
        String[] lines = text.split("\n");
        String merchant = null;
        StringBuilder note = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || !HAS_CJK.matcher(trimmed).find()) {
                continue;
            }
            boolean hasLabel = AMOUNT_LABELED.matcher(trimmed).find();
            boolean hasDate = DATE_ISO.matcher(trimmed).find();
            if (merchant == null && !hasLabel && !hasDate && trimmed.length() <= 16) {
                merchant = trimmed;
                continue;
            }
            if (note.length() > 0) {
                note.append(' ');
            }
            note.append(trimmed);
            if (note.length() >= 100) {
                break;
            }
        }
        if (merchant != null) {
            draft.merchant = merchant;
        }
        return note.length() > 0 ? note.toString() : null;
    }

    private void detectCategory(@NonNull String text,
                                @NonNull List<CategoryEntity> categories,
                                @NonNull TransactionDraft draft) {
        Matcher explicit = CATEGORY_EXPLICIT.matcher(text);
        if (explicit.find()) {
            CategoryEntity matched = findByName(categories, draft.type, explicit.group(1));
            if (matched != null) {
                // 用户明确指定（基线第 20、21 章）：必须采用，置信度拉满。
                draft.categoryId = matched.id;
                draft.categoryConfidence = TransactionDraft.CONFIDENCE_HIGH;
                return;
            }
        }
        CategoryEntity byName = findByNameInText(categories, draft.type, text);
        if (byName != null) {
            draft.categoryId = byName.id;
            draft.categoryConfidence = TransactionDraft.CONFIDENCE_HIGH;
            return;
        }
        CategoryEntity byKeyword = guessByKeyword(text, categories, draft.type);
        if (byKeyword != null) {
            draft.categoryId = byKeyword.id;
            draft.categoryConfidence = TransactionDraft.CONFIDENCE_MEDIUM;
            return;
        }
        // 默认分类：优先「其他」，否则该类型首个分类；置信度低，确认页提示重选。
        CategoryEntity fallback = fallbackCategory(categories, draft.type);
        if (fallback != null) {
            draft.categoryId = fallback.id;
            draft.categoryConfidence = TransactionDraft.CONFIDENCE_LOW;
        }
    }

    /** 精确 / 包含式名称匹配：文本里直接出现了分类名。 */
    @Nullable
    static CategoryEntity findByNameInText(@NonNull List<CategoryEntity> categories, int type,
                                           @NonNull String text) {
        for (CategoryEntity category : categories) {
            if (category.type == type && !category.name.isEmpty()
                    && text.contains(category.name)) {
                return category;
            }
        }
        return null;
    }

    /** 按名称查分类，允许「交通出行」命中「交通」这类包含关系。 */
    @Nullable
    static CategoryEntity findByName(@NonNull List<CategoryEntity> categories, int type,
                                     @Nullable String name) {
        if (name == null || name.trim().isEmpty()) {
            return null;
        }
        String target = name.trim();
        for (CategoryEntity category : categories) {
            if (category.type == type && category.name.equals(target)) {
                return category;
            }
        }
        for (CategoryEntity category : categories) {
            if (category.type == type
                    && (category.name.contains(target) || target.contains(category.name))) {
                return category;
            }
        }
        return null;
    }

    /**
     * 关键词词典 → 内置标准分类名 → 用户实际分类。
     * 词典顺序即优先级，条目内长词在前防止误伤。
     */
    @Nullable
    static CategoryEntity guessByKeyword(@NonNull String text,
                                         @NonNull List<CategoryEntity> categories, int type) {
        String[][] dictionary = type == CategoryEntity.TYPE_INCOME ? INCOME_DICT : EXPENSE_DICT;
        for (String[] entry : dictionary) {
            for (String keyword : entry[1].split("\\|")) {
                if (text.contains(keyword)) {
                    CategoryEntity matched = findByName(categories, type, entry[0]);
                    if (matched != null) {
                        return matched;
                    }
                }
            }
        }
        return null;
    }

    @Nullable
    static CategoryEntity fallbackCategory(@NonNull List<CategoryEntity> categories, int type) {
        for (CategoryEntity category : categories) {
            if (category.type == type && "其他".equals(category.name)) {
                return category;
            }
        }
        for (CategoryEntity category : categories) {
            if (category.type == type) {
                return category;
            }
        }
        return null;
    }

    /**
     * 备注策略：小票取内容行拼接结果；一句话描述保留原文（去换行）。
     * 备注上限 100 字，与账单表 note 列一致。
     */
    @Nullable
    private static String buildNote(@NonNull String text, @Nullable String receiptNote) {
        String note = receiptNote != null && !receiptNote.isEmpty()
                ? receiptNote : text.replace('\n', ' ').trim();
        if (note.length() > 100) {
            note = note.substring(0, 100);
        }
        return note.isEmpty() ? null : note;
    }

    // ------------------------------------------------------------------
    // 关键词词典：{标准分类名, 竖线分隔的关键词}
    // ------------------------------------------------------------------

    private static final String[][] EXPENSE_DICT = {
            {"餐饮", "早茶|午餐|晚餐|早餐|宵夜|外卖|咖啡|奶茶|麦当劳|肯德基|星巴克|海底捞|米线|火锅|烧烤|零食|饮料|餐|饭|吃|喝"},
            {"交通", "顺风车|滴滴|打车|出租|地铁|公交|车费|加油|油费|停车|高速|高铁|火车|机票|飞机|单车|车票"},
            {"购物", "淘宝|京东|拼多多|天猫|超市|便利店|耳机|手机|电脑|衣服|化妆品|百货|买|购"},
            {"娱乐", "剧本杀|电影|游戏|KTV|唱歌|演出|门票|健身|游泳|桌游"},
            {"住房", "房租|水电|物业|燃气|暖气"},
            {"通讯", "电话费|话费|流量|网费|宽带|充值"},
            {"医疗", "挂号|体检|门诊|医院|看病|眼科|药"},
            {"教育", "网课|学费|培训|考试|文具|教材|课程|书"},
            {"旅行", "旅行|酒店|民宿|景点|签证|行李|门票"},
    };

    private static final String[][] INCOME_DICT = {
            {"工资", "工资|薪资|薪水"},
            {"奖金", "年终奖|绩效|奖金"},
            {"兼职", "兼职|外快|副业"},
            {"投资", "股息|分红|利息|基金|股票|理财|收益"},
            {"红包", "红包|礼金"},
            {"其他", "退款|报销|其他"},
    };
}
