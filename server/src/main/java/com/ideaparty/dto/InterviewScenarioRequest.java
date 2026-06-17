package com.ideaparty.dto;

/**
 * Request payload for generating a dynamic interview prompt.
 * 用户在面试模拟弹窗里填写的所有信息
 */
public class InterviewScenarioRequest {

    /** 必填：岗位关键词，如 "高级前端工程师" */
    private String position;

    /** 可选：行业，如 "SaaS" / "电商" */
    private String industry;

    /** 可选：经验年限，如 5 */
    private Integer experienceYears;

    /** 可选：完整 JD 描述（用户从招聘网站粘过来的原文） */
    private String jobDescription;

    /** 可选：简历解析后的纯文本（后端不会再解析，前端先调 /parse-resume 后传过来） */
    private String resumeContent;

    public String getPosition() { return position; }
    public void setPosition(String position) { this.position = position; }

    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }

    public Integer getExperienceYears() { return experienceYears; }
    public void setExperienceYears(Integer experienceYears) { this.experienceYears = experienceYears; }

    public String getJobDescription() { return jobDescription; }
    public void setJobDescription(String jobDescription) { this.jobDescription = jobDescription; }

    public String getResumeContent() { return resumeContent; }
    public void setResumeContent(String resumeContent) { this.resumeContent = resumeContent; }
}
