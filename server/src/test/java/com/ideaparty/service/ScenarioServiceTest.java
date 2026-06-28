package com.ideaparty.service;

import com.ideaparty.dto.InterviewScenarioRequest;
import com.ideaparty.dto.InterviewScenarioResponse;
import com.ideaparty.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScenarioServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ScenarioService scenarioService;

    @Test
    @DisplayName("generateInterviewPrompt throws when position is blank")
    void generateInterviewPrompt_rejectsBlankPosition() {
        InterviewScenarioRequest req = new InterviewScenarioRequest();
        req.setPosition("  ");

        assertThatThrownBy(() ->
                scenarioService.generateInterviewPrompt(UUID.randomUUID(), req)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("岗位");
    }

    @Test
    @DisplayName("generateInterviewPrompt returns valid response on AI failure (fallback path)")
    void generateInterviewPrompt_fallbackWhenAiFails() {
        // 模拟用户存在但没有 API key —— 强制走 fallback
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        InterviewScenarioRequest req = new InterviewScenarioRequest();
        req.setPosition("高级前端工程师");
        req.setIndustry("SaaS");
        req.setExperienceYears(5);

        InterviewScenarioResponse resp = scenarioService.generateInterviewPrompt(userId, req);

        // Fallback 必须给出：非空角色名 + 非空 prompt
        assertThat(resp.getCharacterName()).isNotBlank();
        assertThat(resp.getCharacterName()).contains("面试官");
        assertThat(resp.getPrompt()).isNotBlank();
        assertThat(resp.getPrompt()).contains("面试");
    }

    @Test
    @DisplayName("generateInterviewPrompt fallback works with only position provided")
    void generateInterviewPrompt_fallbackWithMinimalInput() {
        UUID userId = UUID.randomUUID();
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        InterviewScenarioRequest req = new InterviewScenarioRequest();
        req.setPosition("数据分析师");

        InterviewScenarioResponse resp = scenarioService.generateInterviewPrompt(userId, req);

        assertThat(resp.getCharacterName()).contains("数据分析师");
        assertThat(resp.getPrompt()).isNotBlank();
    }
}
