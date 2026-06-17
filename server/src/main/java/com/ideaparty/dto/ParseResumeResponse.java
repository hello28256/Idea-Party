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

    public ParseResumeResponse() {}

    public ParseResumeResponse(String text, int length, String filename, boolean truncated) {
        this.text = text;
        this.length = length;
        this.filename = filename;
        this.truncated = truncated;
    }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }

    public String getFilename() { return filename; }
    public void setFilename(String filename) { this.filename = filename; }

    public boolean isTruncated() { return truncated; }
    public void setTruncated(boolean truncated) { this.truncated = truncated; }
}
