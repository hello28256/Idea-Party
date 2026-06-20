package com.ideaparty.dto;

/**
 * 简历解析响应：上传 docx/pdf 后返回纯文本
 */
public class ParseResumeResponse {

    /** 解析出的纯文本（按段落保留换行） */
    private String text;

    /** 文本字符数 */
    private int length;

    /** 原始文件名 */
    private String filename;

    /** 截断标记：true 表示文本过长被截断 */
    private boolean truncated;

    /**
     * 无参构造：Jackson 反序列化需要默认构造函数来从 JSON 重建对象
     */
    public ParseResumeResponse() {}

    /**
     * 全参构造：Controller 层在解析完成后一次性填充四个字段，避免多次 setter 调用
     */
    public ParseResumeResponse(String text, int length, String filename, boolean truncated) {
        this.text = text;
        this.length = length;
        this.filename = filename;
        this.truncated = truncated;
    }

    /** 返回简历正文：供前端展示或后续 prompt 组装使用 */
    public String getText() { return text; }
    /** 设置简历正文：解析服务在拿到纯文本后回填 */
    public void setText(String text) { this.text = text; }

    /** 返回字符数：用于前端判断是否触达长度上限，辅助决定是否需要截断 */
    public int getLength() { return length; }
    /** 设置字符数：与 text 同步写入，保证 length 始终等于 text.length() */
    public void setLength(int length) { this.length = length; }

    /** 返回上传时的原始文件名：用于前端回显用户上传的是哪份简历 */
    public String getFilename() { return filename; }
    /** 设置原始文件名：保留上传文件的来源信息，方便排查 */
    public void setFilename(String filename) { this.filename = filename; }

    /** 是否被截断：true 时前端应提示用户简历过长、内容可能不完整 */
    public boolean isTruncated() { return truncated; }
    /** 设置截断标记：解析服务按 token/字符上限裁剪后置位 */
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
}
