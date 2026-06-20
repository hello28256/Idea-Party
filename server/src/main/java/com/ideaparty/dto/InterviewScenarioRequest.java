package com.ideaparty.dto;

/**
 * Request payload for generating a dynamic interview prompt.
 * 用户在面试模拟弹窗里填写的所有信息。
 * 作为前端 ScenarioModal 与后端 AIService 之间的传输契约，
 * 由 Jackson 在 Controller 入口反序列化后再透传给 LLM Prompt 拼装器。
 */
public class InterviewScenarioRequest {

    /** 必填：岗位关键词，如 "高级前端工程师"；是生成 Prompt 的唯一必填锚点。 */
    private String position;

    /** 可选：行业，如 "SaaS" / "电商"；让 LLM 用对应行业术语提问。 */
    private String industry;

    /** 可选：经验年限，如 5；用于控制追问深度与术语门槛。 */
    private Integer experienceYears;

    /** 可选：完整 JD 描述（用户从招聘网站粘过来的原文）；原样拼入 Prompt。 */
    private String jobDescription;

    /** 可选：简历解析后的纯文本（后端不会再解析，前端先调 /parse-resume 后传过来）。 */
    private String resumeContent;

    /** 读取岗位关键词；由后端 AIService 用于锚定 Prompt 主题，必须非空。 */
    public String getPosition() { return position; }
    /** 设置岗位关键词；Controller 在请求校验后写入，避免 NPE 下传到 LLM。 */
    public void setPosition(String position) { this.position = position; }

    /** 读取行业；为 Prompt 提供场景化背景，缺省时走通用模板。 */
    public String getIndustry() { return industry; }
    /** 设置行业；前端在面试场景表单中可选填，未填则保持 null。 */
    public void setIndustry(String industry) { this.industry = industry; }

    /** 读取经验年限；用于让 LLM 调整追问深度与术语密度。 */
    public Integer getExperienceYears() { return experienceYears; }
    /** 设置经验年限；前端传入数字字符串经反序列化后填充，null 表示未指定。 */
    public void setExperienceYears(Integer experienceYears) { this.experienceYears = experienceYears; }

    /** 读取完整 JD 原文；拼接进 Prompt 以贴合招聘方真实需求。 */
    public String getJobDescription() { return jobDescription; }
    /** 设置完整 JD 原文；通常由前端从招聘网站粘贴后整体传入。 */
    public void setJobDescription(String jobDescription) { this.jobDescription = jobDescription; }

    /** 读取简历纯文本；后端不再二次解析，直接喂给 Prompt 作为候选人画像。 */
    public String getResumeContent() { return resumeContent; }
    /** 设置简历纯文本；由前端先调用 /parse-resume 抽取后传过来，避免后端重复解析。 */
    public void setResumeContent(String resumeContent) { this.resumeContent = resumeContent; }
}
