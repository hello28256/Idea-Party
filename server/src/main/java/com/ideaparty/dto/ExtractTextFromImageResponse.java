package com.ideaparty.dto;

/**
 * 图片 OCR 识别响应：上传 JD 截图后返回提取的纯文本
 * <p>
 * 为什么存在：前端在「从图片导入角色」场景中上传 JD 截图，后端调用视觉模型 OCR
 * 抽取其中的职位描述文本，再把文本回填到角色创建表单。该 DTO 是后端 → 前端
 * 的契约载体，配套 {@code ExtractTextFromImageRequest} 一同使用。
 */
public class ExtractTextFromImageResponse {

    /**
     * OCR 识别出的纯文本内容。
     * <p>
     * 调用方（Service 层）会从这里读取文本并继续走「角色 prompt 抽取」流水线。
     */
    /** 识别出的文本 */
    private String text;

    /**
     * 识别文本的字符数（{@link #text} 的 length）。
     * <p>
     * 单独返回是为了让前端在拿到文本前就能做长度判断、避免对大字符串重复计算；
     * 同时也是是否触发 {@link #truncated} 截断的判定依据之一。
     */
    /** 文本字符数 */
    private int length;

    /**
     * 原始上传文件名（含扩展名）。
     * <p>
     * 仅用于前端在结果区显示「这是从哪个文件提取的」，不做服务端落库或后处理。
     */
    /** 原始文件名 */
    private String filename;

    /**
     * 截断标记：true 表示识别出的文本超过了后端配置的最大长度上限被截断。
     * <p>
     * 存在意义：避免单次 OCR 结果被直接灌入下游 LLM 触发 token 超限，
     * 前端应根据此标志提示用户「文本已截断，请人工补全」。
     */
    /** 截断标记：true 表示文本过长被截断 */
    private boolean truncated;

    /**
     * 无参构造器。
     * <p>
     * Jackson 反序列化 JSON 时需要无参构造；上层一般不直接 new 这个对象，
     * 而是用 {@link #ExtractTextFromImageResponse(String, int, String, boolean)}。
     */
    public ExtractTextFromImageResponse() {}

    /**
     * 全字段构造器，方便 Service 层在拿到 OCR 结果后一次性装配响应体。
     *
     * @param text      OCR 抽取出的纯文本，可能为空字符串但不应为 null
     * @param length    text 的字符数，传 null 时可填 0 兜底
     * @param filename  原始上传文件名，仅用于回显
     * @param truncated 文本是否被截断，供前端做 UX 提示
     */
    public ExtractTextFromImageResponse(String text, int length, String filename, boolean truncated) {
        this.text = text;
        this.length = length;
        this.filename = filename;
        this.truncated = truncated;
    }

    /**
     * 返回 OCR 抽取出的文本。
     * <p>
     * 调用方：Controller 在序列化响应时由 Jackson 反射调用；前端拿到后会
     * 填入角色创建表单的「描述」输入框。
     *
     * @return 识别文本，可能为空字符串
     */
    public String getText() { return text; }
    /**
     * 设置 OCR 文本。Service 层在装配响应时调用，Controller 不直接使用。
     *
     * @param text 识别出的纯文本
     */
    public void setText(String text) { this.text = text; }

    /**
     * 返回文本字符数。
     * <p>
     * 调用方：前端用于在 UI 上显示「已提取 N 个字符」的统计信息。
     *
     * @return 文本长度（非负整数）
     */
    public int getLength() { return length; }
    /**
     * 设置文本字符数。一般由 Service 在计算 {@code text.length()} 后调用。
     *
     * @param length 文本字符数
     */
    public void setLength(int length) { this.length = length; }

    /**
     * 返回原始上传文件名。
     * <p>
     * 调用方：前端结果区展示「来自 xxx.png」，让用户知道文本对应的来源文件。
     *
     * @return 原始文件名（含扩展名）
     */
    public String getFilename() { return filename; }
    /**
     * 设置原始文件名。由 Service 在接收上传文件时记录后写入响应。
     *
     * @param filename 原始文件名
     */
    public void setFilename(String filename) { this.filename = filename; }

    /**
     * 返回是否被截断。
     * <p>
     * 调用方：前端根据此值决定是否显示「文本已截断，请人工补全」提示横幅。
     *
     * @return true 表示文本过长被截断
     */
    public boolean isTruncated() { return truncated; }
    /**
     * 设置截断标记。Service 在判定文本超过最大长度时置为 true。
     *
     * @param truncated 是否截断
     */
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
}
