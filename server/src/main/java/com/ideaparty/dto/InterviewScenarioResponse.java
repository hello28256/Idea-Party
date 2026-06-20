package com.ideaparty.dto;

/**
 * Response from dynamic interview prompt generation.
 * LLM 会输出 "角色名：xxx\n\n{完整 prompt}"，我们解析后拆成两个字段
 *
 * <p>为什么存在：把 LLM 的一次「自然语言输出」拆成结构化字段，方便前端按字段渲染
 * （角色名进标题、prompt 进设定预览），而不是让前端再去解析字符串。
 *
 * <p>配合方：上游是 {@code AIService} 解析 LLM 输出后的产物，下游被「面试场景」弹窗的接口
 * 返回给前端，再由前端在创建角色时回传给后端的角色/聊天接口。
 */
public class InterviewScenarioResponse {

    /**
     * AI 解析出的角色名，如 "字节跳动 · 高级前端面试官"。
     * 与 prompt 文本开头的 "角色名：xxx" 前缀同源，确保展示与底层设定一致。
     */
    private String characterName;

    /**
     * AI 生成的完整 system prompt。
     * 下游会作为角色的 system message 原样下发，因此必须保留 LLM 输出的完整性（包括换行与占位符）。
     */
    private String prompt;

    /**
     * 无参构造器：Jackson 反序列化场景对象 JSON 时依赖此构造器，因此必须保留。
     */
    public InterviewScenarioResponse() {}

    /**
     * 全参构造器：服务层在解析完 LLM 输出后，用一次调用把角色名与 prompt 注入 DTO，
     * 避免多行 setter 调用造成的中间态泄漏到上游。
     *
     * @param characterName AI 解析得到的角色名（与 prompt 中的前缀保持一致）
     * @param prompt        AI 生成的完整 system prompt，下游会作为角色设定直接喂给聊天室的 system 消息
     */
    public InterviewScenarioResponse(String characterName, String prompt) {
        this.characterName = characterName;
        this.prompt = prompt;
    }

    /**
     * 读取 AI 解析出的角色名。
     * 前端在「面试场景」弹窗里把它作为角色卡片标题展示，因此需要暴露给序列化输出。
     *
     * @return 角色名字符串
     */
    public String getCharacterName() { return characterName; }
    /**
     * 设置角色名：仅在反序列化或测试构造时调用，正常业务流由全参构造器负责赋值。
     *
     * @param characterName 新的角色名
     */
    public void setCharacterName(String characterName) { this.characterName = characterName; }

    /**
     * 读取 AI 生成的完整 system prompt。
     * 前端会原样透传给后续的聊天接口作为角色设定，因此返回值不能做脱敏或裁剪。
     *
     * @return 完整的角色 prompt 文本
     */
    public String getPrompt() { return prompt; }
    /**
     * 设置 prompt：主要服务于 Jackson 反序列化与单测，运行时多走全参构造器。
     *
     * @param prompt 新的角色 prompt
     */
    public void setPrompt(String prompt) { this.prompt = prompt; }
}
