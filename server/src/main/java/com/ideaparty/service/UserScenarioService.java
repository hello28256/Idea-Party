package com.ideaparty.service;

import com.ideaparty.dto.UserScenarioRequest;
import com.ideaparty.dto.UserScenarioResponse;
import com.ideaparty.entity.User;
import com.ideaparty.entity.UserScenario;
import com.ideaparty.repository.UserRepository;
import com.ideaparty.repository.UserScenarioRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 用户私有场景（UserScenario）的业务逻辑层。
 *
 * 核心职责：
 * 1. owner 归属校验：所有按 id 查找的路径都强制携带 ownerId，
 *    避免"用 A 的 id 改/删 B 的场景"越权。
 * 2. 同 owner + 同 title 幂等：复用 CharacterService.create 的三层防重名模式
 *    （前端查重 + 业务层 findFirst + DB 唯一约束），并发 INSERT 触发约束时回退到 findFirst。
 * 3. 输入清洗：把空字符串统一为 null 写入 entity，
 *    避免 UI 上"清空输入框"与"原本就没填"在 DB 层不可区分。
 */
@Service
public class UserScenarioService {

    private static final Logger log = LoggerFactory.getLogger(UserScenarioService.class);

    private final UserScenarioRepository userScenarioRepository;
    private final UserRepository userRepository;

    public UserScenarioService(UserScenarioRepository userScenarioRepository,
                               UserRepository userRepository) {
        this.userScenarioRepository = userScenarioRepository;
        this.userRepository = userRepository;
    }

    /**
     * 列出某用户全部私有场景，按更新时间倒序（最近编辑在前）。
     * 与 CharacterService.findByOwner 同语义，但本接口仅返回当前用户私有场景。
     */
    @Transactional(readOnly = true)
    public List<UserScenarioResponse> listByOwner(UUID ownerId) {
        return userScenarioRepository.findByOwnerIdOrderByUpdatedAtDesc(ownerId)
                .stream()
                .map(UserScenarioResponse::fromEntity)
                .toList();
    }

    /**
     * 创建用户私有场景。
     * 合约：ownerId 必须存在；title 在 (owner_id, title) 维度唯一。
     * 返回持久化后的场景（含 id）；并发 INSERT 触发唯一约束时回退到 findFirst 返回已存在记录。
     */
    @Transactional
    public UserScenarioResponse create(UUID ownerId, UserScenarioRequest request) {
        User owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        String trimmedTitle = request.getTitle() == null ? "" : request.getTitle().trim();
        if (!trimmedTitle.isEmpty()) {
            Optional<UserScenario> existing = userScenarioRepository
                    .findFirstByOwnerIdAndTitle(ownerId, trimmedTitle);
            if (existing.isPresent()) {
                log.info("[DEBUG] UserScenario already exists for owner {} with title '{}', reusing id: {}",
                        ownerId, trimmedTitle, existing.get().getId());
                return UserScenarioResponse.fromEntity(existing.get());
            }
        }

        UserScenario scenario = new UserScenario();
        scenario.setEmoji(request.getEmoji());
        scenario.setTitle(trimmedTitle);
        scenario.setDescription(request.getDescription());
        scenario.setCharacterName(request.getCharacterName());
        scenario.setUserInputLabel(emptyToNull(request.getUserInputLabel()));
        scenario.setUserInputPlaceholder(emptyToNull(request.getUserInputPlaceholder()));
        scenario.setPromptTemplate(request.getPromptTemplate());
        scenario.setOwner(owner);

        UserScenario saved;
        try {
            saved = userScenarioRepository.saveAndFlush(scenario);
        } catch (DataIntegrityViolationException e) {
            // 并发 INSERT 触发了 (owner_id, title) 唯一约束。
            // 此刻另一个并发请求刚把同名场景插了进去，我们重新查一下拿那条真实的记录返回。
            log.info("[DEBUG] Duplicate insert caught by unique constraint for '{}', re-fetching", trimmedTitle);
            return userScenarioRepository
                    .findFirstByOwnerIdAndTitle(ownerId, trimmedTitle)
                    .map(UserScenarioResponse::fromEntity)
                    .orElseThrow(() -> e);
        }
        log.info("[DEBUG] UserScenario created with id: {}, owner: {}", saved.getId(), ownerId);
        return UserScenarioResponse.fromEntity(saved);
    }

    /**
     * 更新用户私有场景。
     * 合约：场景必须存在且属于 ownerId；否则返回 empty（Controller 映射 403）。
     */
    @Transactional
    public Optional<UserScenarioResponse> update(UUID scenarioId, UUID ownerId, UserScenarioRequest request) {
        Optional<UserScenario> existingOpt = userScenarioRepository.findByIdAndOwnerId(scenarioId, ownerId);
        if (existingOpt.isEmpty()) {
            return Optional.empty();
        }
        UserScenario scenario = existingOpt.get();
        scenario.setEmoji(request.getEmoji());
        scenario.setTitle(request.getTitle() == null ? "" : request.getTitle().trim());
        scenario.setDescription(request.getDescription());
        scenario.setCharacterName(request.getCharacterName());
        scenario.setUserInputLabel(emptyToNull(request.getUserInputLabel()));
        scenario.setUserInputPlaceholder(emptyToNull(request.getUserInputPlaceholder()));
        scenario.setPromptTemplate(request.getPromptTemplate());
        // owner 不允许变更；createdAt 由 JPA 维护；updatedAt 由 @PreUpdate 自动刷新
        UserScenario saved = userScenarioRepository.save(scenario);
        log.info("[DEBUG] UserScenario updated: id={}, owner={}", saved.getId(), ownerId);
        return Optional.of(UserScenarioResponse.fromEntity(saved));
    }

    /**
     * 删除用户私有场景（仅 owner 可删）。
     * 返回 true 表示删除成功，false 表示场景不存在或非 owner（Controller 映射 403）。
     * 不级联影响历史房间：Room 只通过 character_id 引用 Character，
     * 删除 scenario 不影响既有的 Character / Room。
     */
    @Transactional
    public boolean deleteIfOwner(UUID scenarioId, UUID ownerId) {
        if (!userScenarioRepository.existsByIdAndOwnerId(scenarioId, ownerId)) {
            return false;
        }
        userScenarioRepository.deleteById(scenarioId);
        log.info("[DEBUG] UserScenario deleted: id={}, owner={}", scenarioId, ownerId);
        return true;
    }

    /**
     * 把空字符串统一为 null，避免 UI 上"清空输入框"与"原本就没填"在 DB 层不可区分。
     * 私有工具方法，不暴露给外部。
     */
    private String emptyToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
